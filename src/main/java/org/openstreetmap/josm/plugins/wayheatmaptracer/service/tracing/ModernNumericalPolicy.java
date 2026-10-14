package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

/** Versioned arithmetic contracts bound into modern capture parameter identities. */
public final class ModernNumericalPolicy {
    /** Fixed encounter-order compensated summation with explicit infinity fallback. */
    public static final String ORDERED_SUM = "ordered-compensated-java17-v1";

    /** Complete B inference uses Java 17 ordered arithmetic and fdlibm 5.3 functions. */
    public static final String B_INFERENCE = "java17-fdlibm53-v1/" + ORDERED_SUM;

    /** Scalar mode uncertainty excludes coherent local slope while retaining noise normalization. */
    public static final String SCALAR_MODE_NOISE = "scalar-mode-second-difference-rms-v2";

    /** Shared modern metric, scalar extraction, orientation and final-cost policy. */
    public static final String SHARED_COST = "shared-modern-cost-fdlibm53-v1/" + ORDERED_SUM
            + "/" + SCALAR_MODE_NOISE;

    private ModernNumericalPolicy() { }
}
