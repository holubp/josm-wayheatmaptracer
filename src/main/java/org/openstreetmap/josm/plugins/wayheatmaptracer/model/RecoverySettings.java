package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Versioned user preference for bounded route recovery and optional junction reconstruction.
 *
 * @param schemaVersion persisted schema revision
 * @param widerDiscovery whether one explicitly bounded wider route search is permitted
 * @param ordinaryRadiusMeters normal decision radius in ground metres
 * @param maximumDiscoveryRadiusMeters largest radius permitted for the explicit wider attempt
 * @param junctionPolicy fixed, legacy bounded move, or explicitly requested reattachment
 * @param reconstructIncidentWays whether incident-way geometry may be reconstructed with reattachment
 */
public record RecoverySettings(
    int schemaVersion,
    boolean widerDiscovery,
    double ordinaryRadiusMeters,
    double maximumDiscoveryRadiusMeters,
    JunctionPolicy junctionPolicy,
    boolean reconstructIncidentWays
) {
    /** Current persisted recovery preference schema. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Initial maximum radius used only when wider discovery is explicitly enabled. */
    public static final double DEFAULT_MAXIMUM_DISCOVERY_RADIUS_METERS = 20.0;

    /** Validates schema, physical radii, and independently authorized network permissions. */
    public RecoverySettings {
        if (schemaVersion < 1 || !Double.isFinite(ordinaryRadiusMeters) || ordinaryRadiusMeters <= 0.0
            || !Double.isFinite(maximumDiscoveryRadiusMeters)
            || maximumDiscoveryRadiusMeters < ordinaryRadiusMeters || junctionPolicy == null
            || !widerDiscovery && Double.compare(maximumDiscoveryRadiusMeters, ordinaryRadiusMeters) != 0
            || reconstructIncidentWays && junctionPolicy != JunctionPolicy.REATTACH) {
            throw new IllegalArgumentException("Recovery settings are inconsistent");
        }
    }

    /** Returns the conservative default with no widened search or new network edit authority. */
    public static RecoverySettings defaults(double ordinaryRadiusMeters) {
        return new RecoverySettings(CURRENT_SCHEMA_VERSION, false, ordinaryRadiusMeters,
            ordinaryRadiusMeters, JunctionPolicy.FIXED, false);
    }

    /** Converts persisted settings into the immutable per-attempt permission object. */
    public RecoveryPermissions toPermissions() {
        return new RecoveryPermissions(widerDiscovery, ordinaryRadiusMeters, maximumDiscoveryRadiusMeters,
            junctionPolicy, reconstructIncidentWays);
    }
}
