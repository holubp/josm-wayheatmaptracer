package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Immutable operation-specific cleanup interval bounded by protected or frozen points.
 *
 * @param startIndex inclusive immutable boundary index
 * @param endIndex inclusive immutable boundary index
 * @param startChainageMeters physical start chainage
 * @param endChainageMeters physical end chainage
 * @param directInteriorPointCount direct usable points strictly inside the interval
 * @param shapeAnalysisEligible whether the interval has enough direct observations for shape analysis
 * @param smoothingEligible whether constrained normal-only smoothing may inspect the interval
 * @param simplificationEligible whether constrained point reduction may inspect the interval
 * @param boundaryReasons deterministic boundary disposition names
 */
public record CleanupInterval(
    int startIndex,
    int endIndex,
    double startChainageMeters,
    double endChainageMeters,
    int directInteriorPointCount,
    boolean shapeAnalysisEligible,
    boolean smoothingEligible,
    boolean simplificationEligible,
    List<String> boundaryReasons
) {
    /** Validates index ordering, physical span, counts, and immutable reasons. */
    public CleanupInterval {
        boundaryReasons = List.copyOf(boundaryReasons);
        if (startIndex < 0 || endIndex <= startIndex || directInteriorPointCount < 0
            || !Double.isFinite(startChainageMeters) || !Double.isFinite(endChainageMeters)
            || endChainageMeters < startChainageMeters) {
            throw new IllegalArgumentException("Cleanup interval is invalid");
        }
    }

    /**
     * Returns the physical interval span in metres.
     *
     * @return non-negative physical interval span
     */
    public double spanMeters() {
        return endChainageMeters - startChainageMeters;
    }
}
