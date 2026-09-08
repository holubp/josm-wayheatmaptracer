package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Inclusive node-occurrence range in one immutable way sequence. */
public record OccurrenceRange(int firstIndex, int lastIndex) {
    /** Requires a nonempty forward range. */
    public OccurrenceRange {
        if (firstIndex < 0 || lastIndex < firstIndex) {
            throw new IllegalArgumentException("Occurrence range must be ordered and nonnegative");
        }
    }

    /** Returns the number of node occurrences. */
    public int size() {
        return lastIndex - firstIndex + 1;
    }
}
