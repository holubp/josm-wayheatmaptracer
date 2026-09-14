package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.GeometricCurvatureOperator.Evaluation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.GeometricCurvatureOperator.InverseMetersVector;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.GeometricCurvatureOperator.MetricGradient;

/** R05/R06: exact geometric-curvature energy and analytic metric gradient. */
class V022GeometricCurvatureOperatorTest {
    private static final InverseMetersVector ZERO_TARGET = new InverseMetersVector(0.0, 0.0);

    @Test
    void forwardCollinearMeshesHaveZeroEnergyRegardlessOfTangentialSpacing() {
        List<List<MetricPoint>> meshes = List.of(
                List.of(point(0.0, 0.0), point(0.1, 0.0), point(4.0, 0.0), point(4.25, 0.0)),
                List.of(point(-8.0, 3.0), point(-7.5, 3.0), point(2.0, 3.0)),
                List.of(point(1.0, -2.0), point(1.5, -1.0), point(4.0, 4.0), point(4.25, 4.5)));

        for (List<MetricPoint> mesh : meshes) {
            Evaluation result = operator(0.7, 2.0, 3.0, zeroTargets(mesh.size())).evaluate(mesh);
            assertEquals(0.0, result.objective());
            result.gradient().forEach(gradient -> {
                assertEquals(0.0, gradient.xObjectivePerMeter());
                assertEquals(0.0, gradient.yObjectivePerMeter());
            });
        }
    }

    @Test
    void reversalIsNotTreatedAsStraightCompatible() {
        Evaluation result = operator(1.0, 1.0, 1.0, List.of(ZERO_TARGET)).evaluate(
                List.of(point(0.0, 0.0), point(2.0, 0.0), point(1.0, 0.0)));

        assertTrue(result.objective() > 0.0);
    }

    @Test
    void halfMeterAlternatingZigzagRetainsPointEightInverseMeterCurvature() {
        double dx = Math.sqrt(0.5 * 0.5 - 0.1 * 0.1);
        List<MetricPoint> mesh = List.of(point(0.0, -0.05), point(dx, 0.05), point(2.0 * dx, -0.05));

        Evaluation result = operator(1.0, 0.5, 1.0, List.of(ZERO_TARGET)).evaluate(mesh);

        assertEquals(0.32, result.objective(), 1.0e-14);
    }

    @Test
    void rightAngleBendHasAnalyticallyComputableEnergy() {
        List<MetricPoint> mesh = List.of(point(0.0, 0.0), point(1.0, 0.0), point(1.0, 1.0));

        Evaluation result = operator(0.5, 1.0, 2.0, List.of(ZERO_TARGET)).evaluate(mesh);

        assertEquals(0.5, result.objective(), 1.0e-14);
    }

    @Test
    void scaledRightAngleRetainsRepresentableAnalyticGradient() {
        for (double scale : List.of(1.0, 1.0e155, 1.0e161, 1.0e162)) {
            GeometricCurvatureOperator operator = operator(scale / 2.0, scale, 2.0,
                    List.of(ZERO_TARGET));
            List<MetricPoint> mesh = List.of(point(0.0, 0.0), point(scale, 0.0), point(scale, scale));

            Evaluation result = operator.evaluate(mesh);
            assertEquals(0.5, result.objective(), 1.0e-14, "scale=" + scale);

            double delta = scale * 1.0e-6;
            double numericalScaledGradient = (operator.evaluate(displaced(mesh, 0, 1, delta)).objective()
                    - operator.evaluate(displaced(mesh, 0, 1, -delta)).objective())
                    / (2.0 * delta) * scale;
            double analyticScaledGradient = result.gradient().get(0).yObjectivePerMeter() * scale;
            assertEquals(0.5, analyticScaledGradient, 1.0e-12, "scale=" + scale);
            assertEquals(numericalScaledGradient, analyticScaledGradient, 1.0e-8,
                    "scale=" + scale);
        }
    }

    @Test
    void huberRadialDerivativeIsZeroAtMatchAndExactBelowAndAboveUnitThreshold() {
        List<MetricPoint> straight = List.of(point(-1.0, 0.0), point(0.0, 0.0), point(1.0, 0.0));

        Evaluation atZero = operator(2.0, 1.0, 3.0,
                List.of(new InverseMetersVector(0.0, 0.0))).evaluate(straight);
        Evaluation below = operator(2.0, 1.0, 3.0,
                List.of(new InverseMetersVector(0.0, 0.25))).evaluate(straight);
        Evaluation above = operator(2.0, 1.0, 3.0,
                List.of(new InverseMetersVector(0.0, 1.0))).evaluate(straight);

        assertEquals(0.0, atZero.objective());
        assertEquals(0.0, atZero.gradient().get(1).yObjectivePerMeter());
        assertEquals(0.375, below.objective(), 1.0e-14);
        assertEquals(6.0, below.gradient().get(1).yObjectivePerMeter(), 1.0e-13);
        assertEquals(4.5, above.objective(), 1.0e-14);
        assertEquals(12.0, above.gradient().get(1).yObjectivePerMeter(), 1.0e-13);
    }

    @Test
    void analyticGradientMatchesBothAxesAtPerturbedUnequalTrialSpacings() {
        List<MetricPoint> trial = List.of(
                point(-0.25, 0.30), point(0.62, -0.08), point(1.91, 0.56),
                point(2.44, -0.41), point(4.30, 0.22));
        GeometricCurvatureOperator operator = operator(0.7, 1.3, 2.1, List.of(
                new InverseMetersVector(0.12, -0.08),
                new InverseMetersVector(-0.22, 0.17),
                new InverseMetersVector(0.05, 0.24)));
        Evaluation analytic = operator.evaluate(trial);

        double epsilon = 1.0e-6;
        for (int index = 0; index < trial.size(); index++) {
            for (int axis = 0; axis < 2; axis++) {
                double numeric = (operator.evaluate(displaced(trial, index, axis, epsilon)).objective()
                        - operator.evaluate(displaced(trial, index, axis, -epsilon)).objective())
                        / (2.0 * epsilon);
                MetricGradient gradient = analytic.gradient().get(index);
                double exact = axis == 0 ? gradient.xObjectivePerMeter() : gradient.yObjectivePerMeter();
                assertEquals(numeric, exact, 2.0e-8,
                        "point=" + index + " axis=" + axis + " numeric=" + numeric + " analytic=" + exact);
            }
        }
    }

    @Test
    void objectiveAndGradientAreRigidMotionCovariant() {
        List<MetricPoint> points = List.of(
                point(-0.3, 0.2), point(0.7, -0.4), point(2.0, 0.5), point(3.4, 0.1));
        List<InverseMetersVector> targets = List.of(
                new InverseMetersVector(0.15, -0.11), new InverseMetersVector(-0.07, 0.21));
        GeometricCurvatureOperator originalOperator = operator(0.8, 1.7, 1.9, targets);
        Evaluation original = originalOperator.evaluate(points);
        double angle = 0.73;
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        List<MetricPoint> transformedPoints = points.stream()
                .map(value -> rotateAndTranslate(value, cosine, sine, 17.0, -9.0)).toList();
        List<InverseMetersVector> transformedTargets = targets.stream()
                .map(value -> rotate(value, cosine, sine)).toList();

        Evaluation transformed = operator(0.8, 1.7, 1.9, transformedTargets).evaluate(transformedPoints);

        assertEquals(original.objective(), transformed.objective(), 2.0e-15);
        for (int index = 0; index < points.size(); index++) {
            MetricGradient expected = rotate(original.gradient().get(index), cosine, sine);
            assertEquals(expected.xObjectivePerMeter(), transformed.gradient().get(index).xObjectivePerMeter(),
                    2.0e-14);
            assertEquals(expected.yObjectivePerMeter(), transformed.gradient().get(index).yObjectivePerMeter(),
                    2.0e-14);
        }
    }

    @Test
    void zeroLambdaProducesExactZeroAfterGeometryValidation() {
        List<MetricPoint> points = List.of(point(0.0, 0.0), point(0.7, 1.3), point(2.8, -0.2));

        Evaluation result = operator(0.4, 1.2, 0.0,
                List.of(new InverseMetersVector(5.0, -7.0))).evaluate(points);

        assertEquals(0.0, result.objective());
        result.gradient().forEach(gradient -> assertEquals(new MetricGradient(0.0, 0.0), gradient));
    }

    @Test
    void constructorAndEvaluationResultsDefensivelyCopyContainers() {
        ArrayList<InverseMetersVector> targets = new ArrayList<>(List.of(ZERO_TARGET));
        GeometricCurvatureOperator operator = operator(1.0, 1.0, 1.0, targets);
        targets.set(0, new InverseMetersVector(20.0, 20.0));

        Evaluation result = operator.evaluate(List.of(point(0.0, 0.0), point(1.0, 0.0), point(2.0, 0.0)));

        assertEquals(0.0, result.objective());
        assertThrows(UnsupportedOperationException.class,
                () -> result.gradient().set(0, new MetricGradient(1.0, 1.0)));
    }

    @Test
    void invalidParametersCountsAndGeometryAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> operator(0.0, 1.0, 1.0, List.of(ZERO_TARGET)));
        assertThrows(IllegalArgumentException.class,
                () -> operator(Double.POSITIVE_INFINITY, 1.0, 1.0, List.of(ZERO_TARGET)));
        assertThrows(IllegalArgumentException.class,
                () -> operator(1.0, Double.NaN, 1.0, List.of(ZERO_TARGET)));
        assertThrows(IllegalArgumentException.class,
                () -> operator(1.0, 1.0, -1.0, List.of(ZERO_TARGET)));
        assertThrows(IllegalArgumentException.class,
                () -> operator(1.0, 1.0, Double.POSITIVE_INFINITY, List.of(ZERO_TARGET)));
        assertThrows(IllegalArgumentException.class,
                () -> new InverseMetersVector(Double.NaN, 0.0));

        GeometricCurvatureOperator oneTarget = operator(1.0, 1.0, 1.0, List.of(ZERO_TARGET));
        assertThrows(IllegalArgumentException.class,
                () -> oneTarget.evaluate(List.of(point(0.0, 0.0), point(1.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> oneTarget.evaluate(
                List.of(point(0.0, 0.0), point(0.0, 0.0), point(1.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> oneTarget.evaluate(
                List.of(point(-Double.MAX_VALUE, 0.0), point(0.0, 0.0), point(Double.MAX_VALUE, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> operator(2.0, 1.0, 1.0,
                List.of(new InverseMetersVector(Double.MAX_VALUE, 0.0))).evaluate(
                        List.of(point(0.0, 0.0), point(1.0, 0.0), point(2.0, 0.0))));
        assertThrows(IllegalArgumentException.class, () -> operator(1.0, 1.0, 1.0,
                Collections.nCopies(GeometricCurvatureOperator.MAXIMUM_MESH_POINTS - 1, ZERO_TARGET)));
    }

    @Test
    void oversizedLazyTargetsAreRejectedBeforeEntriesAreVisited() {
        List<InverseMetersVector> oversizedLazyTargets = new AbstractList<>() {
            @Override
            public InverseMetersVector get(int index) {
                throw new AssertionError("oversized targets must be rejected before traversal");
            }

            @Override
            public int size() {
                return GeometricCurvatureOperator.MAXIMUM_MESH_POINTS - 1;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> operator(1.0, 1.0, 1.0, oversizedLazyTargets));
    }

    private static GeometricCurvatureOperator operator(double pitch, double referenceLength, double lambda,
            List<InverseMetersVector> targets) {
        return new GeometricCurvatureOperator(pitch, referenceLength, lambda, targets);
    }

    private static List<InverseMetersVector> zeroTargets(int meshPointCount) {
        return Collections.nCopies(meshPointCount - 2, ZERO_TARGET);
    }

    private static MetricPoint point(double x, double y) {
        return new MetricPoint(x, y);
    }

    private static List<MetricPoint> displaced(List<MetricPoint> points, int index, int axis, double delta) {
        ArrayList<MetricPoint> copy = new ArrayList<>(points);
        MetricPoint point = copy.get(index);
        copy.set(index, axis == 0 ? point(point.xMeters() + delta, point.yMeters())
                : point(point.xMeters(), point.yMeters() + delta));
        return List.copyOf(copy);
    }

    private static MetricPoint rotateAndTranslate(MetricPoint value, double cosine, double sine,
            double dx, double dy) {
        return point(cosine * value.xMeters() - sine * value.yMeters() + dx,
                sine * value.xMeters() + cosine * value.yMeters() + dy);
    }

    private static InverseMetersVector rotate(InverseMetersVector value, double cosine, double sine) {
        return new InverseMetersVector(
                cosine * value.xPerMeter() - sine * value.yPerMeter(),
                sine * value.xPerMeter() + cosine * value.yPerMeter());
    }

    private static MetricGradient rotate(MetricGradient value, double cosine, double sine) {
        return new MetricGradient(
                cosine * value.xObjectivePerMeter() - sine * value.yObjectivePerMeter(),
                sine * value.xObjectivePerMeter() + cosine * value.yObjectivePerMeter());
    }
}
