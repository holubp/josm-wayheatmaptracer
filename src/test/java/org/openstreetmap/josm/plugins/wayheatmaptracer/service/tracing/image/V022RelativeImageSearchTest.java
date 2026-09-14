package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;

/** G6-07 production image search consumes background-relative route-local evidence. */
class V022RelativeImageSearchTest {
    @Test
    void faintSearchOutputRetainsMeasuredRouteSupport() {
        MetricPoint start = new MetricPoint(1, 3.5);
        MetricPoint end = new MetricPoint(12, 3.5);
        Scene scene = Scene.uniform(14, 7, start, end);
        scene.draw(List.of(start, end), 8e-4);
        DirectionalImageSearchProblem problem = scene.problem(List.of(start, end),
                DirectionalImageSearchProblem.Scope.ORDINARY, false);

        DirectionalImageSearchResult result = new DirectionalImageSearch().solve(problem);

        assertNotEquals(DirectionalImageSearchResult.Status.NO_ROUTE, result.status());
        assertFalse(result.paths().isEmpty());
        ImageCostField image = ImageCostField.fromEvidence(problem.evidence(), problem.fieldName());
        List<MetricPoint> points = result.paths().get(0).points();
        boolean foundDirectInteriorEdge = false;
        for (int index = 1; index < points.size(); index++) {
            MetricPoint startPoint = points.get(index - 1);
            MetricPoint endPoint = points.get(index);
            MetricPoint tangent = new MetricPoint(endPoint.xMeters() - startPoint.xMeters(),
                    endPoint.yMeters() - startPoint.yMeters());
            if (startPoint.distanceTo(endPoint) <= 1.0e-12) {
                continue;
            }
            if (index == 1 || index == points.size() - 1) {
                continue;
            }
            MetricPoint midpoint = new MetricPoint(0.5 * (startPoint.xMeters() + endPoint.xMeters()),
                    0.5 * (startPoint.yMeters() + endPoint.yMeters()));
            ImageCostField.RouteSample sample = image.sampleRoute(midpoint, tangent).orElseThrow();
            assertTrue(sample.directlyLocalized(), "edge " + index + " " + startPoint
                    + " -> " + endPoint + " sample=" + sample);
            foundDirectInteriorEdge = true;
        }
        assertTrue(foundDirectInteriorEdge);
    }
}
