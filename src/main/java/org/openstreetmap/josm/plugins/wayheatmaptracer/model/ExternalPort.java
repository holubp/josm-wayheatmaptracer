package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Captured connection from the editable closure to immutable outside geometry. */
public record ExternalPort(PrimitiveKey wayKey, PrimitiveKey boundaryNodeKey,
    PrimitiveKey outsideNeighborKey, int boundaryOccurrenceIndex, Side side,
    GeographicPoint outsideNeighborCoordinate) {
    /** Which side of the retained boundary occurrence the outside segment occupies. */
    public enum Side { BEFORE, AFTER }

    /** Validates typed identities and explicit direction. */
    public ExternalPort {
        if (wayKey == null || wayKey.type() != PrimitiveKey.Type.WAY || boundaryNodeKey == null
            || boundaryNodeKey.type() != PrimitiveKey.Type.NODE || outsideNeighborKey == null
            || outsideNeighborKey.type() != PrimitiveKey.Type.NODE || boundaryOccurrenceIndex < 0
            || side == null || outsideNeighborCoordinate == null) {
            throw new IllegalArgumentException("External port is inconsistent");
        }
    }
}
