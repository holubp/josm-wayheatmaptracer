package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

/** Conservative numerical bounds for the exact A objective. */
public final class OutwardCostBounds {
    private OutwardCostBounds() { }

    /** Lower enclosure of measured or ordinary-unavailable scalar loss over one source interval. */
    public static double scalarLossLower(double sourceSpanMeters, double pitchMeters,
            double intensityUpperBound) {
        if (!Double.isFinite(sourceSpanMeters) || sourceSpanMeters < 0
                || !Double.isFinite(pitchMeters) || pitchMeters <= 0
                || !Double.isFinite(intensityUpperBound)) return Double.NEGATIVE_INFINITY;
        if (sourceSpanMeters == 0) return 0;
        double ratio = sourceSpanMeters / pitchMeters;
        double rawCoefficient = 1.0 - intensityUpperBound;
        if (!Double.isFinite(ratio) || !Double.isFinite(rawCoefficient))
            return Double.NEGATIVE_INFINITY;
        // One ordinary unavailable interval always has loss 1, even if measured
        // intensity is negative and measured loss could be larger than 1.
        double coefficientLower = Math.nextDown(Math.min(1.0, rawCoefficient));
        double ratioBound = coefficientLower >= 0 ? Math.nextDown(ratio) : Math.nextUp(ratio);
        double product = ratioBound * coefficientLower;
        return Double.isFinite(product) ? Math.nextDown(product) : Double.NEGATIVE_INFINITY;
    }

    /** Bounds a complete path only when every unmeasured later increment is nonnegative. */
    static double completedPathLower(double certifiedPrefix, double nextScalarLower,
            boolean laterIncrementsNonnegative) {
        if (!laterIncrementsNonnegative || !Double.isFinite(certifiedPrefix)
                || !Double.isFinite(nextScalarLower)) return Double.NEGATIVE_INFINITY;
        if (nextScalarLower == 0.0) return certifiedPrefix;
        double sum = certifiedPrefix + nextScalarLower;
        return Double.isFinite(sum) ? Math.nextDown(sum) : Double.NEGATIVE_INFINITY;
    }
}
