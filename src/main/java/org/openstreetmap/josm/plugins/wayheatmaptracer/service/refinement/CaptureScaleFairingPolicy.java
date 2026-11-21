package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;

/** Immutable original-capture calibration for an unvalidated geometric request. */
public record CaptureScaleFairingPolicy(String frozenInputHash, String imageIdentity,
        int width, int height, RasterMetricTransform referenceTransform,
        int maximumNormalPixels, double gain) {
    public CaptureScaleFairingPolicy {
        if (frozenInputHash == null || !frozenInputHash.matches("[0-9a-f]{64}")
                || imageIdentity == null || imageIdentity.isBlank() || width <= 0 || height <= 0
                || referenceTransform == null || maximumNormalPixels < 1 || maximumNormalPixels > 2
                || gain != 0.0 && gain != 1.0)
            throw new IllegalArgumentException("Invalid original-capture policy");
        double scale = referenceTransform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
                ? referenceTransform.rasterPixelsPerSourcePixel() : 1;
        double determinant = (referenceTransform.xAxisEastMetersPerSourcePixel() / scale)
                * (referenceTransform.yAxisNorthMetersPerSourcePixel() / scale)
                - (referenceTransform.xAxisNorthMetersPerSourcePixel() / scale)
                * (referenceTransform.yAxisEastMetersPerSourcePixel() / scale);
        if (determinant == 0 || !Double.isFinite(determinant) || !Double.isFinite(1 / determinant))
            throw new IllegalArgumentException("Unusable original-capture affine");
    }
    public double radiusReferencePixels() { return 20; }
    public double sigmaReferencePixels() { return 20. / 3; }
    public double kernelWeight(double offset) {
        if (!Double.isFinite(offset)) throw new IllegalArgumentException("Nonfinite reference offset");
        if (Math.abs(offset) > 20) return 0;
        double z = offset / sigmaReferencePixels();
        return Math.exp(-.5 * z * z);
    }
    public double fade(double boundaryDistancePixels) {
        if (!Double.isFinite(boundaryDistancePixels)) throw new IllegalArgumentException("Nonfinite boundary distance");
        double t = Math.max(0, Math.min(1, boundaryDistancePixels));
        return t * t * (3 - 2 * t);
    }
    /** Displacement only: the original pixel-center origin phase cancels exactly. */
    public MetricPoint metricDeltaToReference(MetricPoint delta) {
        var a = referenceTransform;
        double scale = a.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL ? a.rasterPixelsPerSourcePixel() : 1;
        double xx = a.xAxisEastMetersPerSourcePixel() / scale, xy = a.yAxisEastMetersPerSourcePixel() / scale;
        double yx = a.xAxisNorthMetersPerSourcePixel() / scale, yy = a.yAxisNorthMetersPerSourcePixel() / scale;
        double determinant = xx * yy - xy * yx;
        return new MetricPoint((yy * delta.xMeters() - xy * delta.yMeters()) / determinant,
                (-yx * delta.xMeters() + xx * delta.yMeters()) / determinant);
    }
    public double referenceDistance(MetricPoint delta) {
        var p = metricDeltaToReference(delta); return Math.hypot(p.xMeters(), p.yMeters());
    }
    /** Metres along a unit ground normal corresponding to one original reference pixel. */
    public double normalPixelMeters(MetricPoint unitMetricNormal) {
        if (Math.abs(Math.hypot(unitMetricNormal.xMeters(), unitMetricNormal.yMeters()) - 1) > 1e-9)
            throw new IllegalArgumentException("Ground normal must be unit length");
        double value = 1 / referenceDistance(unitMetricNormal);
        if (!(value > 0) || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid normal calibration");
        return value;
    }
}
