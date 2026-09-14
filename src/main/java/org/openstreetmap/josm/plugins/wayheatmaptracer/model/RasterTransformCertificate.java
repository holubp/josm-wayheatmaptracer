package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Immutable evidence describing why a retained affine raster transform is exact.
 */
public record RasterTransformCertificate(String method, double maximumErrorMeters,
        double toleranceMeters, int verificationPointCount) {
    /** Rejects incomplete or unsuccessful transform verification. */
    public RasterTransformCertificate {
        if (method == null || method.isBlank() || !Double.isFinite(maximumErrorMeters)
                || !Double.isFinite(toleranceMeters) || maximumErrorMeters < 0.0
                || toleranceMeters < 0.0 || maximumErrorMeters > toleranceMeters
                || verificationPointCount < 0) {
            throw new IllegalArgumentException("Raster transform certificate is not an accepted physical bound");
        }
    }

    /** Certifies a transform whose affine mapping is specified directly rather than fitted. */
    public static RasterTransformCertificate declaredAffine() {
        return new RasterTransformCertificate("declared-affine-v1", 0.0, 0.0, 0);
    }

    /** Certifies an output transform constructed directly from a metric origin and raster axes. */
    public static RasterTransformCertificate exactMetricGrid() {
        return new RasterTransformCertificate("exact-constructed-metric-grid-v1",
                0.0, 0.0, 0);
    }
}
