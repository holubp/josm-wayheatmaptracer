package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Selects one mutually exclusive interpretation of correlated modal evidence. */
public enum ObservationFamily {
    /** Uses directly measured elementary modes. */
    ELEMENTARY,
    /** Uses compatible grouped-parent modes without multiplying their children. */
    GROUPED_PARENT
}
