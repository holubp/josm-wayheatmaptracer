package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Explicit, independently selectable Legacy experiments; historical callers always use ORIGINAL_V02. */
public enum LegacyTrackerPolicy {
    ORIGINAL_V02("legacy-v02"),
    NATIVE_RECIPROCAL_SUPPORT_V1("native-reciprocal-support-v1"),
    GEOMETRY_ONLY_V1("geometry-only-v1"),
    NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1("native-reciprocal-support-geometry-v1"),
    FINE_LOCALIZATION_V1("fine-localization-v1"),
    NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1("native-reciprocal-support-fine-localization-v1"),
    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1("native-reciprocal-support-paired-chord-v1"),
    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2("native-reciprocal-support-paired-chord-v2"),
    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3("native-reciprocal-support-paired-chord-v3");

    private final String id;

    LegacyTrackerPolicy(String id) { this.id = id; }

    /** Stable experiment identifier; no frozen source payload selects these new policies. */
    public String id() { return id; }

    boolean usesNativeSupport() {
        return switch (this) {
            case NATIVE_RECIPROCAL_SUPPORT_V1, NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1,
                    NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1,
                    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3 -> true;
            case ORIGINAL_V02, GEOMETRY_ONLY_V1, FINE_LOCALIZATION_V1 -> false;
        };
    }

    boolean guardsSmoothingGeometry() {
        return switch (this) {
            case GEOMETRY_ONLY_V1, NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1 -> true;
            case ORIGINAL_V02, NATIVE_RECIPROCAL_SUPPORT_V1, FINE_LOCALIZATION_V1,
                    NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1,
                    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3 -> false;
        };
    }

    boolean localizesFineCenters() {
        return switch (this) {
            case FINE_LOCALIZATION_V1, NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1 -> true;
            case ORIGINAL_V02, NATIVE_RECIPROCAL_SUPPORT_V1, GEOMETRY_ONLY_V1,
                    NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1,
                    NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2, NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3 -> false;
        };
    }

    /** Failed geometry-veto experiments remain named for reproducible evidence, never selectable. */
    public boolean isParked() {
        return this == GEOMETRY_ONLY_V1 || this == NATIVE_RECIPROCAL_SUPPORT_GEOMETRY_V1
            || this == FINE_LOCALIZATION_V1 || this == NATIVE_RECIPROCAL_SUPPORT_FINE_LOCALIZATION_V1;
    }

    boolean localizesPairedChord() {
        return this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V1 || this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2
            || this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3;
    }

    boolean correctsSyntheticPositions() {
        return this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V2 || this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3;
    }

    boolean capsDominatedSupportGains() { return this == NATIVE_RECIPROCAL_SUPPORT_PAIRED_CHORD_V3; }

    boolean requiresNativePitch() {
        return usesNativeSupport() || guardsSmoothingGeometry() || localizesFineCenters();
    }

    /** Resolves only supported experiment identifiers, rejecting parked WIP models. */
    public static LegacyTrackerPolicy fromId(String id) {
        for (LegacyTrackerPolicy policy : values()) if (policy.id.equals(id) && !policy.isParked()) return policy;
        throw new IllegalArgumentException("Unknown Legacy tracker policy id");
    }
}
