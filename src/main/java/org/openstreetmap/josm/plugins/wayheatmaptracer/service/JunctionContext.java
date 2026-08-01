package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Endpoint and shared-junction constraints for corridor centerline optimization.
 *
 * @param constraints profile-indexed boundary conditions
 */
public record JunctionContext(List<EndpointConstraint> constraints) {
    /**
     * Makes the constraint list immutable.
     */
    public JunctionContext {
        constraints = List.copyOf(constraints);
    }

    /**
     * Returns an unconstrained context.
     *
     * @return empty context
     */
    public static JunctionContext empty() {
        return new JunctionContext(List.of());
    }

    /**
     * Finds a constraint at one profile.
     *
     * @param profileIndex sampled profile index
     * @return matching constraint, or {@code null}
     */
    public EndpointConstraint at(int profileIndex) {
        return constraints.stream().filter(constraint -> constraint.profileIndex() == profileIndex).findFirst().orElse(null);
    }
}
