package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.List;

/** Bounded image-search outcome; it never represents network or acquisition side effects. */
public record DirectionalImageSearchResult(Status status, List<DirectionalImagePath> paths,
    boolean alternativesTruncated, long evaluatedStates, long evaluatedTransitions,
    boolean widerAttempt, int acquisitionAttempts, String explanation) {
    public enum Status { COMPLETE, AMBIGUOUS, NO_ROUTE, RESOURCE_LIMIT }

    public DirectionalImageSearchResult {
        if (status == null || paths == null || evaluatedStates < 0 || evaluatedTransitions < 0
            || acquisitionAttempts != 0 || explanation == null || explanation.isBlank()
            || (status == Status.NO_ROUTE && !paths.isEmpty())
            || (status == Status.RESOURCE_LIMIT && !alternativesTruncated)) {
            throw new IllegalArgumentException("Directional image result is inconsistent");
        }
        paths = List.copyOf(paths);
    }
}
