package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Longitudinal evidence coverage for one corridor-aware candidate.
 *
 * @param measured whether this candidate was evaluated by the corridor-aware coverage stage
 * @param complete whether every unsupported span is an approved bridge or endpoint approach
 * @param observedProfiles profiles directly associated with the selected strand
 * @param informativeProfiles profiles containing at least one elementary corridor observation
 * @param informativeCoverageRatio directly observed informative-profile fraction
 * @param firstObservedProfile first directly observed profile, or {@code -1}
 * @param lastObservedProfile last directly observed profile, or {@code -1}
 * @param leadingUnsupportedMeters uncovered distance before the first observation
 * @param trailingUnsupportedMeters uncovered distance after the last observation
 * @param maximumInternalUnsupportedProfiles largest internal run without direct observations
 * @param maximumInternalUnsupportedMeters physical span across the largest internal gap
 * @param approvedBridgeCount tracker-approved bounded internal bridges
 * @param informativeEvidenceBeyondTrack whether elementary evidence continues outside an unbridged track
 * @param reason machine-readable completeness result
 */
public record CorridorCoverage(
    boolean measured,
    boolean complete,
    int observedProfiles,
    int informativeProfiles,
    double informativeCoverageRatio,
    int firstObservedProfile,
    int lastObservedProfile,
    double leadingUnsupportedMeters,
    double trailingUnsupportedMeters,
    int maximumInternalUnsupportedProfiles,
    double maximumInternalUnsupportedMeters,
    int approvedBridgeCount,
    boolean informativeEvidenceBeyondTrack,
    String reason
) {
    /**
     * Returns the absence of corridor-aware coverage information.
     *
     * @return unmeasured empty coverage summary
     */
    public static CorridorCoverage empty() {
        return new CorridorCoverage(false, false, 0, 0, 0.0, -1, -1,
            0.0, 0.0, 0, 0.0, 0, false, "not-measured");
    }

    /**
     * Serializes the coverage summary for diagnostics.
     *
     * @return compact JSON object without sensitive data
     */
    public String toJson() {
        return "{"
            + "\"measured\":" + measured + ','
            + "\"complete\":" + complete + ','
            + "\"observedProfiles\":" + observedProfiles + ','
            + "\"informativeProfiles\":" + informativeProfiles + ','
            + "\"informativeCoverageRatio\":" + informativeCoverageRatio + ','
            + "\"firstObservedProfile\":" + firstObservedProfile + ','
            + "\"lastObservedProfile\":" + lastObservedProfile + ','
            + "\"leadingUnsupportedMeters\":" + leadingUnsupportedMeters + ','
            + "\"trailingUnsupportedMeters\":" + trailingUnsupportedMeters + ','
            + "\"maximumInternalUnsupportedProfiles\":" + maximumInternalUnsupportedProfiles + ','
            + "\"maximumInternalUnsupportedMeters\":" + maximumInternalUnsupportedMeters + ','
            + "\"approvedBridgeCount\":" + approvedBridgeCount + ','
            + "\"informativeEvidenceBeyondTrack\":" + informativeEvidenceBeyondTrack + ','
            + "\"reason\":\"" + escape(reason) + "\"}"
            ;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
