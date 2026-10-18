package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Explicit route and network permissions, all conservative on migration. */
public record RecoveryPermissions(
    boolean widerDiscovery,
    double ordinaryRadiusMeters,
    double maximumDiscoveryRadiusMeters,
    JunctionPolicy junctionPolicy,
    boolean reconstructIncidentWays,
    SharedNodeDisconnectionIntent sharedNodeDisconnectionIntent
) {
    /** Preserves historical callers with no shared-node disconnection authority. */
    public RecoveryPermissions(boolean widerDiscovery, double ordinaryRadiusMeters,
            double maximumDiscoveryRadiusMeters, JunctionPolicy junctionPolicy,
            boolean reconstructIncidentWays) {
        this(widerDiscovery, ordinaryRadiusMeters, maximumDiscoveryRadiusMeters, junctionPolicy,
                reconstructIncidentWays, SharedNodeDisconnectionIntent.none());
    }

    /** Validates dependent permissions and bounded radii. */
    public RecoveryPermissions {
        if (!Double.isFinite(ordinaryRadiusMeters) || ordinaryRadiusMeters <= 0.0
            || !Double.isFinite(maximumDiscoveryRadiusMeters)
            || maximumDiscoveryRadiusMeters < ordinaryRadiusMeters || junctionPolicy == null
            || !widerDiscovery && maximumDiscoveryRadiusMeters != ordinaryRadiusMeters
            || reconstructIncidentWays && junctionPolicy != JunctionPolicy.REATTACH
            || sharedNodeDisconnectionIntent == null) {
            throw new IllegalArgumentException("Recovery permissions are inconsistent");
        }
    }

    /** Returns permissions with no widened or network edit operation. */
    public static RecoveryPermissions disabled(double ordinaryRadiusMeters) {
        return new RecoveryPermissions(false, ordinaryRadiusMeters, ordinaryRadiusMeters,
            JunctionPolicy.FIXED, false, SharedNodeDisconnectionIntent.none());
    }

    /** Stable identity text; empty permissions retain the historical five-field value. */
    public String canonicalIdentity() {
        String legacy = "RecoveryPermissions[widerDiscovery=" + widerDiscovery
                + ", ordinaryRadiusMeters=" + ordinaryRadiusMeters
                + ", maximumDiscoveryRadiusMeters=" + maximumDiscoveryRadiusMeters
                + ", junctionPolicy=" + junctionPolicy
                + ", reconstructIncidentWays=" + reconstructIncidentWays;
        return sharedNodeDisconnectionIntent.isEmpty() ? legacy + "]"
                : legacy + ", sharedNodeDisconnectionIntentHash="
                    + sharedNodeDisconnectionIntent.canonicalHash() + "]";
    }
}
