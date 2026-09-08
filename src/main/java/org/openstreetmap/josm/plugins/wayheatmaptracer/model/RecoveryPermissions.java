package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Explicit route and network permissions, all conservative on migration. */
public record RecoveryPermissions(
    boolean widerDiscovery,
    double ordinaryRadiusMeters,
    double maximumDiscoveryRadiusMeters,
    JunctionPolicy junctionPolicy,
    boolean reconstructIncidentWays
) {
    /** Validates dependent permissions and bounded radii. */
    public RecoveryPermissions {
        if (!Double.isFinite(ordinaryRadiusMeters) || ordinaryRadiusMeters <= 0.0
            || !Double.isFinite(maximumDiscoveryRadiusMeters)
            || maximumDiscoveryRadiusMeters < ordinaryRadiusMeters || junctionPolicy == null
            || !widerDiscovery && maximumDiscoveryRadiusMeters != ordinaryRadiusMeters
            || reconstructIncidentWays && junctionPolicy != JunctionPolicy.REATTACH) {
            throw new IllegalArgumentException("Recovery permissions are inconsistent");
        }
    }

    /** Returns permissions with no widened or network edit operation. */
    public static RecoveryPermissions disabled(double ordinaryRadiusMeters) {
        return new RecoveryPermissions(false, ordinaryRadiusMeters, ordinaryRadiusMeters,
            JunctionPolicy.FIXED, false);
    }
}
