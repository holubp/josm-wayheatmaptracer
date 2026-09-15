package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Stable typed identity for one point carried from raw inference through final geometry. */
public sealed interface FinalRoutePointId
        permits FinalRoutePointId.ExistingWayNodeOccurrence,
                FinalRoutePointId.GeneratedCandidatePoint {
    /** Exact occurrence of one existing node in the captured selected-way sequence. */
    record ExistingWayNodeOccurrence(PrimitiveKey wayKey, PrimitiveKey nodeKey,
            int originalOccurrenceIndex) implements FinalRoutePointId {
        /** Requires typed existing way/node identities and a nonnegative original index. */
        public ExistingWayNodeOccurrence {
            if (wayKey == null || nodeKey == null
                    || wayKey.type() != PrimitiveKey.Type.WAY
                    || nodeKey.type() != PrimitiveKey.Type.NODE
                    || wayKey.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || nodeKey.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || originalOccurrenceIndex < 0) {
                throw new IllegalArgumentException("Existing route occurrence identity is invalid");
            }
        }
    }

    /** Stable identity of one engine-produced candidate or quadrature point. */
    record GeneratedCandidatePoint(String candidateId, int originalPointIndex)
            implements FinalRoutePointId {
        /** Requires a stable candidate identity and nonnegative original point index. */
        public GeneratedCandidatePoint {
            if (candidateId == null || candidateId.isBlank() || originalPointIndex < 0) {
                throw new IllegalArgumentException("Generated route point identity is invalid");
            }
        }
    }
}
