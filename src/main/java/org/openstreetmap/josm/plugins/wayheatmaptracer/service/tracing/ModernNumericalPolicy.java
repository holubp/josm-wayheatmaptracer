package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

/** Versioned arithmetic contracts bound into modern capture parameter identities. */
public final class ModernNumericalPolicy {
    /** Fixed encounter-order compensated summation with explicit infinity fallback. */
    public static final String ORDERED_SUM = "ordered-compensated-java17-v1";

    /** Complete B inference uses Java 17 ordered arithmetic and fdlibm 5.3 functions. */
    public static final String B_INFERENCE = "java17-fdlibm53-v1/" + ORDERED_SUM;

    /** Shared modern metric, orientation and final-cost leaves use fdlibm 5.3. */
    public static final String SHARED_COST = "shared-modern-cost-fdlibm53-v1/" + ORDERED_SUM;

    private ModernNumericalPolicy() { }
}
