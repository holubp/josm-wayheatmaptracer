package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import java.util.List;

/** Affine-axis sampling must agree exactly with the sampler's actual inverse transform. */
class V022RasterAxisSpanTest {
    @Test void spanUsesTheSameInverseForManagedVisibleSkewedAndAnisotropicGrids() {
        var origin = new MetricPoint(-20, 31);
        var left = new MetricPoint(12.3, -5.2);
        var right = new MetricPoint(18.7, 8.5);
        for (var transform : List.of(RasterMetricTransform.managed(origin, 1.75, 4),
                RasterMetricTransform.visible(origin, .25),
                RasterMetricTransform.metricGrid(origin, .05, 0, 0, 1),
                RasterMetricTransform.metricGrid(origin, .3, .4, -.2, .5))) {
            var l = transform.metricToPixelCenter(left);
            var r = transform.metricToPixelCenter(right);
            double expected = Math.max(Math.abs(r.x()-l.x()), Math.abs(r.y()-l.y()));
            assertEquals(expected, transform.maximumRasterAxisSpan(left.xMeters(), left.yMeters(),
                    right.xMeters(), right.yMeters()), 0, transform.transformId());
            assertEquals(expected, transform.maximumRasterAxisSpan(right.xMeters(), right.yMeters(),
                    left.xMeters(), left.yMeters()), 0);
        }
    }
}
