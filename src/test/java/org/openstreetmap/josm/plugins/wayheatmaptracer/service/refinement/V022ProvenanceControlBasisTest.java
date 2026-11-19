package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class V022ProvenanceControlBasisTest {
    private static MetricPoint p(double x, double y) { return new MetricPoint(x, y); }

    @Test void negativeZeroComponentSurvivesDependentMotionOnOnlyTheOtherAxis() {
        var horizontal = List.of(p(-.37, 0), p(-0.0, 0), p(.63, 0));
        var rows = List.of(FinalControlBasis.Row.independent(0), FinalControlBasis.Row.dependent(1, 0, 2, .37),
                FinalControlBasis.Row.independent(2));
        var basis = new FinalControlBasis(horizontal, rows, Set.of());
        var shifted = basis.lift(List.of(p(-.37, 1), horizontal.get(1), p(.63, 1)));
        assertEquals(1, shifted.get(1).yMeters());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(shifted.get(1).xMeters()));

        var vertical = List.of(p(0, -.37), p(0, -0.0), p(0, .63));
        var otherAxis = new FinalControlBasis(vertical, rows, Set.of()).lift(List.of(p(1, -.37), vertical.get(1), p(1, .63)));
        assertEquals(1, otherAxis.get(1).xMeters());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(otherAxis.get(1).yMeters()));
    }

    @Test void irregularNonMidpointSubdivisionLiftsDisplacementsAndKeepsExactOrigin() {
        var original = List.of(p(-0.0, 2), p(3, 7), p(9, -4), p(12, 1), p(15, 5));
        var rows = List.of(FinalControlBasis.Row.independent(0),
                FinalControlBasis.Row.independent(1),
                FinalControlBasis.Row.dependent(2, 1, 3, 0.4),
                FinalControlBasis.Row.independent(3),
                FinalControlBasis.Row.independent(4));
        var basis = new FinalControlBasis(original, rows, Set.of(0, 4));
        var zero = basis.lift(List.of(original.get(0), original.get(1), p(123, 456), original.get(3), original.get(4)));
        assertSame(original.get(2), zero.get(2));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(zero.get(0).xMeters()));

        var changed = new ArrayList<>(List.of(original.get(0), p(4, 10), p(99, 99), p(11, -2), original.get(4)));
        var lifted = basis.lift(changed);
        assertEquals(9.2, lifted.get(2).xMeters(), 1e-12);
        assertEquals(-3.4, lifted.get(2).yMeters(), 1e-12);
        assertSame(original.get(0), lifted.get(0));
        assertThrows(IllegalArgumentException.class, () -> basis.lift(List.of(p(1, 0), original.get(1), p(0, 0), original.get(3), original.get(4))));
    }

    @Test void pullbackIsAdjointAndDependentGradientSlotsAreZero() {
        var original = List.of(p(0, 0), p(1, 2), p(2, .4), p(4, -1), p(8, 0));
        var basis = new FinalControlBasis(original, List.of(
                FinalControlBasis.Row.independent(0), FinalControlBasis.Row.independent(1),
                FinalControlBasis.Row.dependent(2, 1, 3, 0.4), FinalControlBasis.Row.independent(3),
                FinalControlBasis.Row.independent(4)), Set.of(0, 4));
        var full = List.of(p(3, -2), p(-1, 4), p(5, 7), p(2, -3), p(-4, 1));
        var pulled = basis.pullback(full);
        assertEquals(p(0, 0), pulled.get(2));
        assertEquals(p(-1 + 0.6 * 5, 4 + 0.6 * 7), pulled.get(1));
        assertEquals(2 + 0.4 * 5, pulled.get(3).xMeters(), 1e-12);
        assertEquals(-3 + 0.4 * 7, pulled.get(3).yMeters(), 1e-12);

        var direction = List.of(p(0, 0), p(0.3, -0.8), p(999, 999), p(-0.5, 0.2), p(0, 0));
        var liftedDirection = basis.lift(List.of(original.get(0), p(1.3, 1.2), p(999, 999), p(3.5, -0.8), original.get(4)));
        double lhs = 0, rhs = 0;
        for (int i = 0; i < full.size(); i++) lhs += full.get(i).xMeters() * (liftedDirection.get(i).xMeters() - original.get(i).xMeters()) + full.get(i).yMeters() * (liftedDirection.get(i).yMeters() - original.get(i).yMeters());
        for (int i : basis.independentIndices()) rhs += pulled.get(i).xMeters() * direction.get(i).xMeters() + pulled.get(i).yMeters() * direction.get(i).yMeters();
        assertEquals(lhs, rhs, 1e-12);

        // Coordinate finite differences of a linear scalar objective match C^T g.
        double epsilon = 1e-6;
        for (int control : List.of(1, 3)) {
            var plus = new ArrayList<>(original); plus.set(control, p(original.get(control).xMeters() + epsilon, original.get(control).yMeters()));
            var minus = new ArrayList<>(original); minus.set(control, p(original.get(control).xMeters() - epsilon, original.get(control).yMeters()));
            double numerical = (dot(full, basis.lift(plus)) - dot(full, basis.lift(minus))) / (2 * epsilon);
            assertEquals(pulled.get(control).xMeters(), numerical, 1e-7);
            plus = new ArrayList<>(original); plus.set(control, p(original.get(control).xMeters(), original.get(control).yMeters() + epsilon));
            minus = new ArrayList<>(original); minus.set(control, p(original.get(control).xMeters(), original.get(control).yMeters() - epsilon));
            numerical = (dot(full, basis.lift(plus)) - dot(full, basis.lift(minus))) / (2 * epsilon);
            assertEquals(pulled.get(control).yMeters(), numerical, 1e-7);
        }
    }

    @Test void descriptorIsDefensiveAndRejectsInvalidAncestry() {
        var original = new ArrayList<>(List.of(p(0, 0), p(1, 1), p(2, 0), p(3, 1), p(4, 0)));
        var rows = new ArrayList<>(List.of(FinalControlBasis.Row.independent(0),
                FinalControlBasis.Row.independent(1), FinalControlBasis.Row.dependent(2, 1, 3, 0.25),
                FinalControlBasis.Row.independent(3), FinalControlBasis.Row.independent(4)));
        var basis = new FinalControlBasis(original, rows, Set.of());
        original.set(1, p(100, 100)); rows.clear();
        assertEquals(p(1, 1), basis.initialCoordinates().get(1));
        assertThrows(UnsupportedOperationException.class, () -> basis.rows().clear());
        assertThrows(IllegalArgumentException.class, () -> new FinalControlBasis(List.of(p(0,0), p(1,0), p(2,0)), List.of(FinalControlBasis.Row.independent(0), FinalControlBasis.Row.dependent(1,0,2,.5), FinalControlBasis.Row.independent(2)), Set.of(1)));
        assertThrows(IllegalArgumentException.class, () -> new FinalControlBasis(List.of(p(0,0), p(1,0), p(2,0), p(3,0), p(4,0)), List.of(FinalControlBasis.Row.independent(0), FinalControlBasis.Row.independent(1), FinalControlBasis.Row.dependent(2,1,4,.5), FinalControlBasis.Row.independent(3), FinalControlBasis.Row.independent(4)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new FinalControlBasis(List.of(p(0,0), p(1,0), p(2,0)), List.of(FinalControlBasis.Row.independent(1), FinalControlBasis.Row.independent(0), FinalControlBasis.Row.independent(2)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> FinalControlBasis.Row.dependent(1, 0, 2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new FinalControlBasis(
                List.of(p(0,0), p(1,0), p(2,0), p(3,0), p(4,0), p(5,0)),
                List.of(FinalControlBasis.Row.independent(0), FinalControlBasis.Row.independent(1),
                        FinalControlBasis.Row.dependent(2,1,4,.8), FinalControlBasis.Row.dependent(3,1,4,.2),
                        FinalControlBasis.Row.independent(4), FinalControlBasis.Row.independent(5)), Set.of()));
        var overflowBasis = new FinalControlBasis(List.of(p(0,0), p(1e308,0), p(0,0), p(-1e308,0), p(0,0)),
                List.of(FinalControlBasis.Row.independent(0), FinalControlBasis.Row.independent(1),
                        FinalControlBasis.Row.dependent(2,1,3,.5), FinalControlBasis.Row.independent(3),
                        FinalControlBasis.Row.independent(4)), Set.of(0,4));
        assertThrows(IllegalArgumentException.class, () -> overflowBasis.lift(
                List.of(p(0,0), p(-1e308,0), p(0,0), p(1e308,0), p(0,0))));
    }

    @Test void public121CollapseUsesFactualNonmidpointAncestry() {
        var points = new ArrayList<MetricPoint>();
        for (int i = 0; i < 124; i++) points.add(p(i, i * 0.5));
        points.set(120, p(141.99999999995435, 44.185950068882));
        points.set(121, p(142.80004021329498, 44.20801961943482));
        points.set(122, p(144.00000000003445, 44.24112117199831));
        var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i < 124; i++) rows.add(i == 121 ? FinalControlBasis.Row.dependent(121, 120, 122, .400020106654292) : FinalControlBasis.Row.independent(i));
        var basis = new FinalControlBasis(points, rows, Set.of(0, 123));
        var values = new ArrayList<>(points);
        values.set(120, p(142.41077610873847, 43.44854290874837));
        values.set(121, p(142.6016924265334, 43.51197633914581));
        values.set(122, p(142.6325736946977, 43.48260705699294));
        var lifted = basis.lift(values);
        assertEquals(142.49949960272954, lifted.get(121).xMeters(), 1e-12);
        assertEquals(43.46216925296225, lifted.get(121).yMeters(), 1e-12);
        assertNotEquals(values.get(121), lifted.get(121));
        assertEquals(points.size(), lifted.size());
    }

    private static double dot(List<MetricPoint> gradient, List<MetricPoint> values) {
        double result = 0;
        for (int i = 0; i < gradient.size(); i++) result += gradient.get(i).xMeters() * values.get(i).xMeters() + gradient.get(i).yMeters() * values.get(i).yMeters();
        return result;
    }
}
