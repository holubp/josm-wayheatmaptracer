package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmissionMappingTest {
    @Test
    void identityMappingOwnsEveryObservationAndVertex() {
        EmissionMapping mapping = identity(3);

        assertEquals(OptionalInt.of(0), mapping.emittedVertexForObservation(0));
        assertEquals(OptionalInt.of(1), mapping.emittedVertexForObservation(1));
        assertEquals(OptionalInt.of(2), mapping.emittedVertexForObservation(2));
        assertEquals(OptionalInt.empty(), mapping.consumingArcForObservation(1));
    }

    @Test
    void mixedEmissionAndConsumptionPreservesSourceRowsAndMapsConsumedRowsToArcs() {
        EmissionMapping mapping = EmissionMapping.create(5,
            List.of(new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.ObservationVertex(2),
                new EmissionMapping.ObservationVertex(4)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Consumed(0),
                new EmissionMapping.Emitted(1), new EmissionMapping.Consumed(1),
                new EmissionMapping.Emitted(2)),
            Set.of(2));

        assertEquals(OptionalInt.of(0), mapping.emittedVertexForObservation(0));
        assertEquals(OptionalInt.of(0), mapping.consumingArcForObservation(1));
        assertEquals(OptionalInt.of(1), mapping.emittedVertexForObservation(2));
        assertEquals(OptionalInt.of(1), mapping.consumingArcForObservation(3));
        assertEquals(OptionalInt.of(2), mapping.emittedVertexForObservation(4));
        assertEquals(5, mapping.observationCount());
        assertEquals(3, mapping.vertexOwners().size());
    }

    @Test
    void boundaryVerticesRemainExplicitAndDoNotRequireObservationRows() {
        EmissionMapping mapping = EmissionMapping.create(3,
            List.of(new EmissionMapping.BoundaryVertex("left"),
                new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.ObservationVertex(1),
                new EmissionMapping.ObservationVertex(2),
                new EmissionMapping.BoundaryVertex("right")),
            List.of(new EmissionMapping.Emitted(1), new EmissionMapping.Emitted(2),
                new EmissionMapping.Emitted(3)), Set.of());

        assertEquals(OptionalInt.of(1), mapping.emittedVertexForObservation(0));
        assertEquals(OptionalInt.empty(), mapping.consumingArcForObservation(1));
        assertEquals(OptionalInt.of(3), mapping.emittedVertexForObservation(2));
    }

    @Test
    void rejectsConsumptionOnAnArcTouchingBoundaryVertex() {
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            List.of(new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.BoundaryVertex("inserted"),
                new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Consumed(1),
                new EmissionMapping.Emitted(2)), Set.of()));
    }

    @Test
    void rejectsInvalidCountsAndMandatoryRows() {
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(1,
            List.of(new EmissionMapping.ObservationVertex(0), new EmissionMapping.ObservationVertex(0)),
            List.of(new EmissionMapping.Emitted(0)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            List.of(new EmissionMapping.ObservationVertex(0)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(0),
                new EmissionMapping.Emitted(0)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            List.of(new EmissionMapping.ObservationVertex(0), new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Consumed(0),
                new EmissionMapping.Emitted(1)), Set.of(3)));
    }

    @Test
    void firstAndLastSourceRowsCannotBeConsumed() {
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(4,
            List.of(new EmissionMapping.ObservationVertex(1), new EmissionMapping.ObservationVertex(3)),
            List.of(new EmissionMapping.Consumed(0), new EmissionMapping.Emitted(0),
                new EmissionMapping.Consumed(0), new EmissionMapping.Emitted(1)), Set.of()));
    }

    @Test
    void rejectsMissingObservationOwnerThroughCanonicalConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new EmissionMapping(3,
            List.of(new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(1)), Set.of()));
    }

    @Test
    void rejectsReciprocalDuplicateOrderNullRangeAndMandatoryViolations() {
        assertInvalid(List.of(new EmissionMapping.ObservationVertex(0),
            new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(0),
                new EmissionMapping.Emitted(1)));
        assertInvalid(List.of(new EmissionMapping.ObservationVertex(0),
            new EmissionMapping.ObservationVertex(0), new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(1),
                new EmissionMapping.Emitted(2)));
        assertInvalid(List.of(new EmissionMapping.ObservationVertex(0),
            new EmissionMapping.ObservationVertex(2), new EmissionMapping.ObservationVertex(1)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(2),
                new EmissionMapping.Emitted(1)));
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            java.util.Arrays.asList(new EmissionMapping.ObservationVertex(0), null,
                new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(1),
                new EmissionMapping.Emitted(2)), Set.of()));
        assertInvalid(List.of(new EmissionMapping.ObservationVertex(0),
            new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Emitted(1),
                new EmissionMapping.Emitted(4)));
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            List.of(new EmissionMapping.ObservationVertex(0), new EmissionMapping.ObservationVertex(2)),
            List.of(new EmissionMapping.Emitted(0), new EmissionMapping.Consumed(0),
                new EmissionMapping.Emitted(1)), Set.of(1)));
    }

    @Test
    void boundaryOccurrenceIdsAreNonblankAndUnique() {
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(2,
            List.of(new EmissionMapping.BoundaryVertex(" "),
                new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.ObservationVertex(1)),
            List.of(new EmissionMapping.Emitted(1), new EmissionMapping.Emitted(2)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(2,
            List.of(new EmissionMapping.BoundaryVertex("same"),
                new EmissionMapping.ObservationVertex(0),
                new EmissionMapping.BoundaryVertex("same"),
                new EmissionMapping.ObservationVertex(1)),
            List.of(new EmissionMapping.Emitted(1), new EmissionMapping.Emitted(3)), Set.of()));
    }

    @Test
    void allCollectionsAreDefensivelyCopiedAndImmutable() {
        List<EmissionMapping.VertexOwner> vertices = new ArrayList<>(List.of(
            new EmissionMapping.ObservationVertex(0), new EmissionMapping.ObservationVertex(2)));
        List<EmissionMapping.ObservationOwner> observations = new ArrayList<>(List.of(
            new EmissionMapping.Emitted(0), new EmissionMapping.Consumed(0),
            new EmissionMapping.Emitted(1)));
        Set<Integer> mandatory = new HashSet<>(Set.of(2));
        EmissionMapping mapping = EmissionMapping.create(3, vertices, observations, mandatory);
        vertices.clear();
        observations.clear();
        mandatory.clear();

        assertEquals(2, mapping.vertexOwners().size());
        assertEquals(3, mapping.observationOwners().size());
        assertEquals(Set.of(2), mapping.mandatoryObservationRows());
        assertThrows(UnsupportedOperationException.class, () -> mapping.vertexOwners().clear());
        assertThrows(UnsupportedOperationException.class, () -> mapping.observationOwners().clear());
        assertThrows(UnsupportedOperationException.class, () -> mapping.mandatoryObservationRows().clear());
    }

    @Test
    void queriesRejectOutOfRangeRows() {
        EmissionMapping mapping = identity(3);
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.emittedVertexForObservation(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.consumingArcForObservation(3));
    }

    private static EmissionMapping identity(int count) {
        List<EmissionMapping.VertexOwner> vertices = new ArrayList<>();
        List<EmissionMapping.ObservationOwner> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            vertices.add(new EmissionMapping.ObservationVertex(index));
            observations.add(new EmissionMapping.Emitted(index));
        }
        return EmissionMapping.create(count, vertices, observations, Set.of());
    }

    private static void assertInvalid(List<EmissionMapping.VertexOwner> vertices,
        List<EmissionMapping.ObservationOwner> observations) {
        assertThrows(IllegalArgumentException.class, () -> EmissionMapping.create(3,
            vertices, observations, Set.of()));
    }
}
