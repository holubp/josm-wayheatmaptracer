package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Classifies whether one reconciled cleanup point may move or only bound local work. */
public enum CleanupPointDisposition {
    /** Fixed, endpoint, tagged, shared, selected, or junction anchor. */
    PROTECTED_ANCHOR,
    /** Direct scalar/profile evidence suitable for local cleanup analysis. */
    DIRECT_USABLE,
    /** Bounded interpolation retained only as an immutable boundary. */
    FROZEN_INTERPOLATED,
    /** Unsupported corridor evidence retained only as an immutable boundary. */
    FROZEN_UNSUPPORTED,
    /** Profile anchor outside captured raster support. */
    FROZEN_OFF_RASTER,
    /** Profile has no usable scalar heatmap signal. */
    FROZEN_NO_SIGNAL,
    /** Fine/coarse scale-space evidence conflicts. */
    FROZEN_SCALE_CONFLICT;

    /**
     * Returns whether this disposition must remain exact during cleanup.
     *
     * @return {@code true} for protected or locally frozen evidence
     */
    public boolean immutable() {
        return this != DIRECT_USABLE;
    }
}
