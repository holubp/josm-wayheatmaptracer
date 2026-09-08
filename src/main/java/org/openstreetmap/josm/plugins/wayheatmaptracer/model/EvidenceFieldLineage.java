package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;

/** Typed acquisition, scalar-source, and post-mapping operation lineage for one evidence field. */
public record EvidenceFieldLineage(
    AcquisitionKind acquisitionKind,
    DerivationKind derivationKind,
    String sourcePalette,
    EvidenceCorrelationGroup correlationGroup,
    boolean completeAggregate,
    List<String> scalarOperations
) {
    /** Source acquisition path. */
    public enum AcquisitionKind { MANAGED_TILE, VISIBLE_RENDER, FROZEN_REPLAY, SYNTHETIC }

    /** Original color-to-scalar or direct-scalar derivation; filtering never overwrites it. */
    public enum DerivationKind { NATIVE_PALETTE_MAPPING, ALL_COLOR_AGGREGATE, DIRECT_INTENSITY }

    /** Validates explicit source lineage, complete aggregation, and immutable scalar operations. */
    public EvidenceFieldLineage {
        if (acquisitionKind == null || derivationKind == null || sourcePalette == null
            || sourcePalette.isBlank() || correlationGroup == null || scalarOperations == null
            || scalarOperations.stream().anyMatch(operation -> operation == null || operation.isBlank())
            || derivationKind == DerivationKind.ALL_COLOR_AGGREGATE && !completeAggregate) {
            throw new IllegalArgumentException("Evidence field lineage is incomplete");
        }
        scalarOperations = List.copyOf(scalarOperations);
    }

    /** Creates unfiltered scalar-source lineage. */
    public EvidenceFieldLineage(AcquisitionKind acquisitionKind, DerivationKind derivationKind,
        String sourcePalette, EvidenceCorrelationGroup correlationGroup, boolean completeAggregate) {
        this(acquisitionKind, derivationKind, sourcePalette, correlationGroup, completeAggregate, List.of());
    }

    /** Returns lineage for a scalar-domain filtered derivative while retaining its source semantics. */
    public EvidenceFieldLineage filtered(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Scalar operation identifier is required");
        }
        List<String> operations = new ArrayList<>(scalarOperations);
        operations.add(operation);
        return new EvidenceFieldLineage(acquisitionKind, derivationKind,
            sourcePalette, correlationGroup, completeAggregate, operations);
    }

    /** Returns whether this field has undergone scalar-domain filtering. */
    public boolean filtered() {
        return !scalarOperations.isEmpty();
    }
}
