package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Named, versioned normal-selection policies for the Legacy V0.2 profile sampler. */
public enum LegacyProfileNormalPolicy {
    /** Historical immediate-neighbor normal selection. */
    IMMEDIATE_V1("legacy-immediate-v1"),
    /** Radius-three secants constrained by original fixed-occurrence fences. */
    PROTECTED_SECANT3_V1("legacy-protected-secant3-v1");

    private final String id;

    LegacyProfileNormalPolicy(String id) {
        this.id = id;
    }

    /** @return stable diagnostic/persistence identifier */
    public String id() {
        return id;
    }

    /**
     * Resolves a stable policy identifier without accepting aliases or future unknown values.
     *
     * @param id policy identifier
     * @return matching policy
     * @throws IllegalArgumentException for null or unknown identifiers
     */
    public static LegacyProfileNormalPolicy fromId(String id) {
        for (LegacyProfileNormalPolicy policy : values()) {
            if (policy.id.equals(id)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown Legacy profile normal policy id");
    }
}
