package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/** Central strict bilinear sampler for immutable scalar evidence. */
public final class StrictScalarSampler {
    private StrictScalarSampler() {
        // Utility class.
    }

    /**
     * Samples one metric point only when it is authorized and every contributing corner is observed.
     * No valid neighbor is renormalized across an invalid or out-of-raster corner.
     */
    public static OptionalDouble sample(ScalarEvidenceField field, RasterMetricTransform transform,
            MetricRegion region, MetricPoint point) {
        if (field == null || transform == null || region == null || point == null) {
            throw new IllegalArgumentException("Strict scalar sampling inputs are incomplete");
        }
        if (!region.contains(point)) {
            return OptionalDouble.empty();
        }
        RasterPoint raster = transform.metricToPixelCenter(point);
        return field.sampleBilinear(raster.x(), raster.y());
    }
}
