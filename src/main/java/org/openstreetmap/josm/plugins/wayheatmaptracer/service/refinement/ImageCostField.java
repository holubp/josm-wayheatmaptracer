package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/**
 * Frozen scalar-image interpolation field used by refitting and final quality checks.
 *
 * <p>The field copies scalar intensity and validity at construction. Bilinear interpolation is
 * permitted only when all four source cells are valid; no edge clamping or missing-value fill is
 * performed.</p>
 */
public final class ImageCostField {
    /** Interpolated scalar evidence costs and their analytic metric-coordinate gradients. */
    public record Sample(double intensity, double presenceCost, double centerCost,
            double presenceGradientX, double presenceGradientY,
            double centerGradientX, double centerGradientY) {
    }

    private final int width;
    private final int height;
    private final double[] intensity;
    private final boolean[] valid;
    private final RasterMetricTransform transform;
    private final MetricRegion decisionRegion;
    private final double sourcePitchMeters;

    /** Creates a deep immutable image field from already mapped scalar evidence. */
    public ImageCostField(ScalarEvidenceField field, RasterMetricTransform transform,
            MetricRegion decisionRegion, double sourcePitchMeters) {
        if (field == null || transform == null || decisionRegion == null
                || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0) {
            throw new IllegalArgumentException("Image cost field inputs must be complete and physical");
        }
        this.width = field.width();
        this.height = field.height();
        this.intensity = field.copiedValues();
        this.valid = field.copiedValidity();
        this.transform = transform;
        this.decisionRegion = decisionRegion;
        this.sourcePitchMeters = sourcePitchMeters;
    }

    /** Returns the physical source-pixel pitch used for uncertainty and trust limits. */
    public double sourcePitchMeters() {
        return sourcePitchMeters;
    }

    /** Returns whether a point has complete bilinear image support inside the decision region. */
    public boolean supports(MetricPoint point) {
        return sample(point).isPresent();
    }

    /**
     * Returns bilinear center cost and its analytic gradient at a metric point.
     *
     * <p>The center cost is {@code (1-intensity)^2}; it is common across engines and has no
     * engine-objective semantics.</p>
     */
    public Optional<Sample> sample(MetricPoint point) {
        if (!decisionRegion.contains(point)) {
            return Optional.empty();
        }
        RasterPoint raster = transform.metricToPixelCenter(point);
        int x0 = (int) Math.floor(raster.x());
        int y0 = (int) Math.floor(raster.y());
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height
                || !isValid(x0, y0) || !isValid(x1, y0) || !isValid(x0, y1) || !isValid(x1, y1)) {
            return Optional.empty();
        }
        double tx = raster.x() - x0;
        double ty = raster.y() - y0;
        double i00 = value(x0, y0);
        double i10 = value(x1, y0);
        double i01 = value(x0, y1);
        double i11 = value(x1, y1);
        double top = i00 + tx * (i10 - i00);
        double bottom = i01 + tx * (i11 - i01);
        double interpolated = top + ty * (bottom - top);
        double derivativeRasterX = symmetricDerivativeX(x0, y0, ty, tx, i00, i10, i01, i11);
        double derivativeRasterY = symmetricDerivativeY(x0, y0, tx, ty, i00, i10, i01, i11);

        double determinant = transform.xAxisEastMetersPerSourcePixel()
                * transform.yAxisNorthMetersPerSourcePixel()
                - transform.xAxisNorthMetersPerSourcePixel()
                * transform.yAxisEastMetersPerSourcePixel();
        double scale = transform.rasterPixelsPerSourcePixel();
        double drxDmx = scale * transform.yAxisNorthMetersPerSourcePixel() / determinant;
        double drxDmy = -scale * transform.yAxisEastMetersPerSourcePixel() / determinant;
        double dryDmx = -scale * transform.xAxisNorthMetersPerSourcePixel() / determinant;
        double dryDmy = scale * transform.xAxisEastMetersPerSourcePixel() / determinant;
        double gradientIntensityX = derivativeRasterX * drxDmx + derivativeRasterY * dryDmx;
        double gradientIntensityY = derivativeRasterX * drxDmy + derivativeRasterY * dryDmy;
        double presence = Math.max(1.0e-6, interpolated);
        double residual = 1.0 - interpolated;
        return Optional.of(new Sample(interpolated, -Math.log(presence), residual * residual,
                -gradientIntensityX / presence, -gradientIntensityY / presence,
                -2.0 * residual * gradientIntensityX, -2.0 * residual * gradientIntensityY));
    }

    /** Returns mean center cost along a segment, or positive infinity when support is incomplete. */
    public double meanSegmentCost(MetricPoint start, MetricPoint end) {
        double length = start.distanceTo(end);
        int samples = Math.max(1, (int) Math.ceil(length / Math.min(2.0, sourcePitchMeters / 2.0)));
        double total = 0.0;
        for (int index = 0; index < samples; index++) {
            double fraction = (index + 0.5) / samples;
            MetricPoint point = interpolate(start, end, fraction);
            Optional<Sample> sample = sample(point);
            if (sample.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            total += sample.orElseThrow().centerCost();
        }
        return total / samples;
    }

    /** Returns mean center cost along a polyline, weighted by physical segment length. */
    public double meanPolylineCost(java.util.List<MetricPoint> points) {
        double weighted = 0.0;
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            double segmentLength = points.get(index - 1).distanceTo(points.get(index));
            double cost = meanSegmentCost(points.get(index - 1), points.get(index));
            if (!Double.isFinite(cost)) {
                return Double.POSITIVE_INFINITY;
            }
            weighted += segmentLength * cost;
            length += segmentLength;
        }
        return length > 0.0 ? weighted / length : Double.POSITIVE_INFINITY;
    }

    private boolean isValid(int x, int y) {
        return valid[y * width + x];
    }

    private double value(int x, int y) {
        return intensity[y * width + x];
    }

    private double symmetricDerivativeX(int x0, int y0, double ty, double tx,
            double i00, double i10, double i01, double i11) {
        double right = (1.0 - ty) * (i10 - i00) + ty * (i11 - i01);
        if (Math.abs(tx) > 1.0e-10 || x0 == 0 || !isValid(x0 - 1, y0) || !isValid(x0 - 1, y0 + 1)) {
            return right;
        }
        double left = (1.0 - ty) * (i00 - value(x0 - 1, y0))
                + ty * (i01 - value(x0 - 1, y0 + 1));
        return 0.5 * (left + right);
    }

    private double symmetricDerivativeY(int x0, int y0, double tx, double ty,
            double i00, double i10, double i01, double i11) {
        double lower = (1.0 - tx) * (i01 - i00) + tx * (i11 - i10);
        if (Math.abs(ty) > 1.0e-10 || y0 == 0 || !isValid(x0, y0 - 1) || !isValid(x0 + 1, y0 - 1)) {
            return lower;
        }
        double upper = (1.0 - tx) * (i00 - value(x0, y0 - 1))
                + tx * (i10 - value(x0 + 1, y0 - 1));
        return 0.5 * (upper + lower);
    }

    private static MetricPoint interpolate(MetricPoint start, MetricPoint end, double fraction) {
        return new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
    }
}
