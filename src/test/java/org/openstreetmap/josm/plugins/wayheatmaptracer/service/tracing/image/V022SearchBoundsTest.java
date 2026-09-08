package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class V022SearchBoundsTest {
    @Test
    void t047NormalSearchNeverSilentlyUsesWiderEvidenceRegion() {
        Scene scene = Scene.uniform(21, 12, new MetricPoint(1, 1), new MetricPoint(19, 1));
        scene.draw(List.of(new MetricPoint(1, 1), new MetricPoint(1, 8), new MetricPoint(19, 8),
            new MetricPoint(19, 1)), 1.0);
        scene.setDecisionMaximumY(4.0);

        assertEquals(DirectionalImageSearchResult.Status.NO_ROUTE, new DirectionalImageSearch()
            .solve(scene.problem(List.of(scene.start(), scene.end()),
                DirectionalImageSearchProblem.Scope.ORDINARY, false)).status());
    }

    @Test
    void t048ExplicitWiderSearchRetainsSeparateAttemptLineage() {
        Scene scene = Scene.uniform(21, 12, new MetricPoint(1, 1), new MetricPoint(19, 1));
        scene.draw(List.of(new MetricPoint(1, 1), new MetricPoint(1, 8), new MetricPoint(19, 8),
            new MetricPoint(19, 1)), 1.0);

        DirectionalImageSearchResult result = new DirectionalImageSearch().solve(scene.problem(
            List.of(scene.start(), scene.end()), DirectionalImageSearchProblem.Scope.WIDER_DISCOVERY, false));
        assertTrue(result.widerAttempt());
        assertFalse(result.paths().isEmpty());
    }

    @Test
    void t049RouteRefinementAuthorityCannotExceedDecisionMask() {
        Scene scene = Scene.straight(14, 7);
        scene.setDecisionMaximumY(3.0);

        assertFalse(DirectionalImageGeometry.segmentAuthorized(scene.evidence().decisionRegion(),
            new MetricPoint(1, 1), new MetricPoint(12, 5)));
    }

    @Test
    void t050EverySegmentInteriorIsCheckedAgainstDecisionMask() {
        Scene scene = Scene.straight(14, 7);
        scene.cutDecisionGap(6.0, 8.0);

        assertEquals(DirectionalImageSearchResult.Status.NO_ROUTE, new DirectionalImageSearch()
            .solve(scene.problem(List.of(scene.start(), scene.end()),
                DirectionalImageSearchProblem.Scope.ORDINARY, false)).status());
    }

    @Test
    void t051InvalidPixelsAreNotReacquiredDuringOfflineSearch() {
        Scene scene = Scene.straight(14, 7);
        scene.invalidateColumn(7);
        DirectionalImageSearchResult result = new DirectionalImageSearch().solve(scene.problem(
            List.of(scene.start(), scene.end()), DirectionalImageSearchProblem.Scope.ORDINARY, false));

        assertEquals(DirectionalImageSearchResult.Status.NO_ROUTE, result.status());
        assertEquals(0, result.acquisitionAttempts());
    }

    @Test
    void t052TopologyPermissionDoesNotAuthorizeRouteWidening() {
        Scene scene = Scene.straight(14, 7);

        assertThrows(IllegalArgumentException.class, () -> scene.problem(List.of(scene.start(), scene.end()),
            DirectionalImageSearchProblem.Scope.WIDER_DISCOVERY, false, true));
    }
}
