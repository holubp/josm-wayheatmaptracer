package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence;

class LegacyTopologyPlannerTest {
    private static final List<ProjectedPoint> SOURCE = List.of(point(0, 0), point(10, 0), point(20, 0));
    private static final List<ProjectedPoint> RIDGE = List.of(
            point(0, 0), point(5, 4), point(15, 4), point(20, 0));
    private static final List<Occurrence> FACTS = List.of(
            new Occurrence(0, true, true), new Occurrence(1, false, true), new Occurrence(2, true, true));

    @Test void insertsTheActualMovableTopologyTargetBetweenRidgeVertices() {
        var assignments = LegacyTopologyPlanner.preciseAssignments(SOURCE, RIDGE, FACTS);

        assertEquals(3, assignments.size());
        var middle = assignments.get(1);
        assertEquals(1, middle.sourceIndex());
        assertEquals(point(10, 0), middle.source());
        assertEquals(point(10, 4), middle.target());
        assertEquals(.5, middle.sourceFraction(), 1e-15);
        assertEquals(.5, middle.previewFraction(), 1e-15);
        assertFalse(middle.fixed());
        assertEquals(point(0, 0), assignments.get(0).target());
        assertEquals(point(20, 0), assignments.get(2).target());
        assertTrue(assignments.get(0).fixed());
        assertTrue(assignments.get(2).fixed());
        assertEquals(List.of(point(0, 0), point(5, 4), point(10, 4), point(15, 4), point(20, 0)),
                LegacyTopologyPlanner.constrain(SOURCE, RIDGE, FACTS));
    }

    @Test void rejectsAFixedMiddleMissingFromThePreview() {
        var fixed = List.of(new Occurrence(0, true, true), new Occurrence(1, true, true),
                new Occurrence(2, true, true));
        assertThrows(IllegalStateException.class,
                () -> LegacyTopologyPlanner.preciseAssignments(SOURCE, RIDGE, fixed));
        assertThrows(IllegalStateException.class,
                () -> LegacyTopologyPlanner.constrain(SOURCE, RIDGE, fixed));
    }

    @Test void keepsFixedCoordinatesExactWithinTheExistingProjectionTolerance() {
        var facts = List.of(new Occurrence(0, true, true), new Occurrence(1, true, true));
        var source = List.of(point(0, 0), point(20, 0));
        var close = List.of(point(0, 1e-7), point(20, 1e-7));
        var assignments = LegacyTopologyPlanner.preciseAssignments(source, close, facts);
        assertEquals(source, assignments.stream().map(LegacyTopologyPlanner.Assignment::target).toList());
        assertThrows(IllegalStateException.class, () -> LegacyTopologyPlanner.preciseAssignments(source,
                List.of(point(0, 1.01e-7), point(20, 1.01e-7)), facts));
    }

    @Test void movableEndpointsClipOverhangWithoutAssigningOrdinaryInteriorNodes() {
        var facts = List.of(new Occurrence(0, false, true), new Occurrence(1, false, false),
                new Occurrence(2, false, true));
        var preview = List.of(point(-5, 4), point(0, 4), point(10, 4), point(20, 4), point(25, 4));
        var assignments = LegacyTopologyPlanner.preciseAssignments(SOURCE, preview, facts);
        assertEquals(List.of(0, 2), assignments.stream().map(LegacyTopologyPlanner.Assignment::sourceIndex).toList());
        assertEquals(point(0, 4), assignments.get(0).target());
        assertEquals(point(20, 4), assignments.get(1).target());
        assertEquals(1.0 / 6, assignments.get(0).previewFraction(), 1e-15);
        assertEquals(5.0 / 6, assignments.get(1).previewFraction(), 1e-15);
        assertEquals(List.of(point(0, 4), point(10, 4), point(20, 4)),
                LegacyTopologyPlanner.constrain(SOURCE, preview, facts));
    }

    @Test void retainsProjectedSearchWindowAndNearFractionFallback() {
        var shortSource = List.of(point(0, 0), point(0, 50), point(100, 0));
        var shortPreview = List.of(point(0, 0), point(100, 0));
        // The 35 projected-unit window admits the closest point at fraction zero.
        assertEquals(point(0, 0), LegacyTopologyPlanner.preciseAssignments(shortSource, shortPreview, FACTS)
                .get(1).target());

        var longSource = List.of(point(0, 0), point(0, 100), point(1000, 0));
        var longPreview = List.of(point(0, 0), point(1000, 0));
        // Fraction zero lies outside 0.08, so the native fallback uses the source fraction.
        var fallback = LegacyTopologyPlanner.preciseAssignments(longSource, longPreview, FACTS).get(1);
        assertEquals(90.4987562112089, fallback.target().east(), 1e-12);
        assertEquals(0, fallback.target().north());
        assertEquals(fallback.sourceFraction(), fallback.previewFraction());
    }

    @Test void rejectsTargetsThatReverseSourceOrder() {
        var reversed = List.of(point(20, 0), point(10, 0), point(0, 0));
        assertThrows(IllegalStateException.class,
                () -> LegacyTopologyPlanner.preciseAssignments(SOURCE, reversed, FACTS));
        assertThrows(IllegalStateException.class,
                () -> LegacyTopologyPlanner.constrain(SOURCE, reversed, FACTS));
    }

    @Test void requiresFactsForEveryUniqueSourceOccurrenceInSourceOrder() {
        var invalid = List.of(
                FACTS.subList(0, 2),
                List.of(FACTS.get(0), FACTS.get(1), FACTS.get(1)),
                List.of(FACTS.get(1), FACTS.get(0), FACTS.get(2)),
                List.of(FACTS.get(0), FACTS.get(1), new Occurrence(3, false, true)));
        for (var facts : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> LegacyTopologyPlanner.preciseAssignments(SOURCE, RIDGE, facts));
        }
        assertThrows(IllegalArgumentException.class, () -> new Occurrence(-1, false, false));
    }

    @Test void preservesCoincidentAssignmentsWhileDeduplicatingOnlyGeometry() {
        var source = List.of(point(0, 0), point(10, 0), point(10, 0), point(20, 0));
        var facts = List.of(new Occurrence(0, true, true), new Occurrence(1, false, true),
                new Occurrence(2, false, true), new Occurrence(3, true, true));
        var assignments = LegacyTopologyPlanner.preciseAssignments(source, RIDGE, facts);
        assertEquals(List.of(0, 1, 2, 3),
                assignments.stream().map(LegacyTopologyPlanner.Assignment::sourceIndex).toList());
        assertEquals(point(10, 4), assignments.get(1).target());
        assertEquals(point(10, 4), assignments.get(2).target());
        assertEquals(.5, assignments.get(1).sourceFraction());
        assertEquals(.5, assignments.get(2).sourceFraction());
        assertEquals(List.of(point(0, 0), point(5, 4), point(10, 4), point(15, 4), point(20, 0)),
                LegacyTopologyPlanner.constrain(source, RIDGE, facts));
    }

    @Test void zeroLengthCoordinatesKeepBothOccurrencesButCannotFormConstrainedGeometry() {
        var source = List.of(point(0, 0), point(0, 0));
        var facts = List.of(new Occurrence(0, true, true), new Occurrence(1, true, true));
        var assignments = LegacyTopologyPlanner.preciseAssignments(source, source, facts);
        assertEquals(2, assignments.size());
        assertEquals(0, assignments.get(0).sourceFraction());
        assertEquals(1, assignments.get(1).sourceFraction());
        assertEquals(0, assignments.get(0).previewFraction());
        assertEquals(0, assignments.get(1).previewFraction());
        assertThrows(IllegalStateException.class, () -> LegacyTopologyPlanner.constrain(source, source, facts));
    }

    @Test void outputsAreImmutableDetachedValuesIndependentOfCallerContainers() {
        var source = new ArrayList<>(SOURCE);
        var preview = new ArrayList<>(RIDGE);
        var facts = new ArrayList<>(FACTS);
        var assignments = LegacyTopologyPlanner.preciseAssignments(source, preview, facts);
        var constrained = LegacyTopologyPlanner.constrain(source, preview, facts);
        source.clear(); preview.clear(); facts.clear();
        assertEquals(3, assignments.size());
        assertEquals(point(10, 4), assignments.get(1).target());
        assertEquals(5, constrained.size());
        assertThrows(UnsupportedOperationException.class, () -> assignments.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> constrained.remove(0));
        assertDoesNotThrow(() -> DetachedValueVerifier.verify(assignments));
        assertDoesNotThrow(() -> DetachedValueVerifier.verify(constrained));
        assertDoesNotThrow(() -> DetachedValueVerifier.verify(FACTS));
    }

    private static ProjectedPoint point(double east, double north) {
        return new ProjectedPoint(east, north);
    }
}
