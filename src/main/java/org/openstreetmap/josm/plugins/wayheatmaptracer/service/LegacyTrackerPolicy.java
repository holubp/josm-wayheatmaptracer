package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Explicit, independently selectable Legacy experiments; historical callers always use ORIGINAL_V02. */
public enum LegacyTrackerPolicy {
    ORIGINAL_V02("legacy-v02"),
    NATIVE_RECIPROCAL_SUPPORT_V1("native-reciprocal-support-v1"),
    GEOMETRY_ONLY_V1("geometry-only-v1"),
    NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1("native-reciprocal-support-geometry-v1");

    private final String id;

    LegacyTrackerPolicy(String id) { this.id = id; }

    /** Stable experiment identifier; no frozen source payload selects these new policies. */
    public String id() { return id; }

    boolean usesNativeSupport() {
        return switch (this) {
            case NATIVE_RECIPROCAL_SUPPORT_V1, NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1 -> true;
            case ORIGINAL_V02, GEOMETRY_ONLY_V1 -> false;
        };
    }

    boolean guardsSmoothingGeometry() {
        return switch (this) {
            case GEOMETRY_ONLY_V1, NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1 -> true;
            case ORIGINAL_V02, NATIVE_RECIPROCAL_SUPPORT_V1 -> false;
        };
    }

    boolean requiresNativePitch() { return usesNativeSupport() || guardsSmoothingGeometry(); }

    /** Resolves only supported experiment identifiers, rejecting parked WIP models. */
    public static LegacyTrackerPolicy fromId(String id) {
        for (LegacyTrackerPolicy policy : values()) if (policy.id.equals(id)) return policy;
        throw new IllegalArgumentException("Unknown Legacy tracker policy id");
    }
}
