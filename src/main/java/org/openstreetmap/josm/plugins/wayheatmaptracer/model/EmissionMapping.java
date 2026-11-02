package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/** Immutable correspondence between source observations and emitted geometry vertices. */
public record EmissionMapping(
    int observationCount,
    List<VertexOwner> vertexOwners,
    List<ObservationOwner> observationOwners,
    Set<Integer> mandatoryObservationRows
) {
    /** Validates reciprocal ownership and retains defensive immutable collection copies. */
    public EmissionMapping {
        if (observationCount < 2 || vertexOwners == null || observationOwners == null
                || mandatoryObservationRows == null) {
            throw new IllegalArgumentException("Emission mapping requires observations, vertices, and mandatory rows");
        }
        if (vertexOwners.stream().anyMatch(owner -> owner == null)
                || observationOwners.stream().anyMatch(owner -> owner == null)
                || mandatoryObservationRows.stream().anyMatch(row -> row == null)) {
            throw new IllegalArgumentException("Emission mapping ownership entries cannot be null");
        }
        vertexOwners = List.copyOf(vertexOwners);
        observationOwners = List.copyOf(observationOwners);
        mandatoryObservationRows = Set.copyOf(mandatoryObservationRows);
        if (vertexOwners.size() < 2 || observationOwners.size() != observationCount) {
            throw new IllegalArgumentException("Emission mapping geometry and observation counts are invalid");
        }

        Set<String> boundaryIds = new HashSet<>();
        int previousObservation = -1;
        for (VertexOwner owner : vertexOwners) {
            if (owner instanceof ObservationVertex observationVertex) {
                int row = observationVertex.observationIndex();
                if (row < 0 || row >= observationCount || row <= previousObservation) {
                    throw new IllegalArgumentException("Emitted observation vertices must be in strict source order");
                }
                previousObservation = row;
            } else if (owner instanceof BoundaryVertex boundaryVertex) {
                String id = boundaryVertex.occurrenceId();
                if (id == null || id.isBlank() || !boundaryIds.add(id)) {
                    throw new IllegalArgumentException("Boundary occurrence ids must be nonblank and unique");
                }
            } else {
                throw new IllegalArgumentException("Vertex owner is required");
            }
        }

        for (Integer mandatory : mandatoryObservationRows) {
            if (mandatory == null || mandatory < 0 || mandatory >= observationCount) {
                throw new IllegalArgumentException("Mandatory observation row is outside the source sequence");
            }
        }

        for (int row = 0; row < observationCount; row++) {
            ObservationOwner owner = observationOwners.get(row);
            if (owner instanceof Emitted emitted) {
                int vertex = emitted.vertexIndex();
                if (vertex < 0 || vertex >= vertexOwners.size()
                        || !(vertexOwners.get(vertex) instanceof ObservationVertex observationVertex)
                        || observationVertex.observationIndex() != row) {
                    throw new IllegalArgumentException("Emitted observation ownership is not reciprocal");
                }
            } else if (owner instanceof Consumed consumed) {
                int arc = consumed.arcIndex();
                if (arc < 0 || arc >= vertexOwners.size() - 1
                        || !(vertexOwners.get(arc) instanceof ObservationVertex left)
                        || !(vertexOwners.get(arc + 1) instanceof ObservationVertex right)
                        || left.observationIndex() >= row || row >= right.observationIndex()
                        || mandatoryObservationRows.contains(row)) {
                    throw new IllegalArgumentException(
                        "Consumed observations require a nonmandatory row strictly inside an emitted observation arc");
                }
            } else {
                throw new IllegalArgumentException("Observation owner is required for every source row");
            }
        }

        for (int vertex = 0; vertex < vertexOwners.size(); vertex++) {
            VertexOwner owner = vertexOwners.get(vertex);
            if (owner instanceof ObservationVertex observationVertex) {
                ObservationOwner observationOwner = observationOwners.get(observationVertex.observationIndex());
                if (!(observationOwner instanceof Emitted emitted) || emitted.vertexIndex() != vertex) {
                    throw new IllegalArgumentException("Observation vertex has no exact reciprocal emission");
                }
            }
        }
        if (!(observationOwners.get(0) instanceof Emitted)
                || !(observationOwners.get(observationCount - 1) instanceof Emitted)) {
            throw new IllegalArgumentException("Original first and last observations must be emitted");
        }
        for (int row : mandatoryObservationRows) {
            if (!(observationOwners.get(row) instanceof Emitted)) {
                throw new IllegalArgumentException("Mandatory observations must be emitted");
            }
        }
    }

    /** Creates an emission mapping. */
    public static EmissionMapping create(int observationCount, List<VertexOwner> vertexOwners,
            List<ObservationOwner> observationOwners, Set<Integer> mandatoryObservationRows) {
        return new EmissionMapping(observationCount, vertexOwners, observationOwners, mandatoryObservationRows);
    }

    /** Returns the emitted vertex for one source observation, when directly emitted. */
    public OptionalInt emittedVertexForObservation(int observationIndex) {
        checkObservationIndex(observationIndex);
        ObservationOwner owner = observationOwners.get(observationIndex);
        return owner instanceof Emitted emitted ? OptionalInt.of(emitted.vertexIndex()) : OptionalInt.empty();
    }

    /** Returns the consumed geometry arc for one source observation, when consumed. */
    public OptionalInt consumingArcForObservation(int observationIndex) {
        checkObservationIndex(observationIndex);
        ObservationOwner owner = observationOwners.get(observationIndex);
        return owner instanceof Consumed consumed ? OptionalInt.of(consumed.arcIndex()) : OptionalInt.empty();
    }

    private void checkObservationIndex(int index) {
        if (index < 0 || index >= observationCount) {
            throw new IndexOutOfBoundsException("Observation row is outside the mapping: " + index);
        }
    }

    /** Ownership of one retained source observation row. */
    public sealed interface ObservationOwner permits Emitted, Consumed { }
    /** One source row that directly owns an emitted vertex. */
    public record Emitted(int vertexIndex) implements ObservationOwner { }
    /** One source row consumed by the emitted arc with this zero-based index. */
    public record Consumed(int arcIndex) implements ObservationOwner { }

    /** Ownership of one emitted geometry vertex. */
    public sealed interface VertexOwner permits ObservationVertex, BoundaryVertex { }
    /** An emitted vertex directly representing one original source observation. */
    public record ObservationVertex(int observationIndex) implements VertexOwner { }
    /** An emitted fixed/boundary vertex with a stable occurrence identity. */
    public record BoundaryVertex(String occurrenceId) implements VertexOwner { }
}
