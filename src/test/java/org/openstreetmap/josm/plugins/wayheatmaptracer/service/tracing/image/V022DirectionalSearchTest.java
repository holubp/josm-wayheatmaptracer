package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;

class V022DirectionalSearchTest {
    @Test
    void t039AStarMatchesZeroHeuristicDijkstraOnSmallGraph() {
        DirectionalImageSearchResult astar = solve(Scene.straight(14, 7), false);
        DirectionalImageSearchResult dijkstra = solve(Scene.straight(14, 7), true);

        assertEquals(dijkstra.paths().get(0).objective(), astar.paths().get(0).objective(), 1e-12);
        assertEquals(dijkstra.paths().get(0).points(), astar.paths().get(0).points());
    }

    @Test
    void t040HeadingsUseActualIntegerVectorBearings() {
        DirectionalImageSearchResult result = solve(Scene.diagonal(14, 7), false);

        assertTrue(result.paths().get(0).headingsRadians().stream()
            .anyMatch(bearing -> Math.abs(bearing - Math.atan2(1.0, 2.0)) < 1e-12));
    }

    @Test
    void t041DiagonalCannotJumpAcrossInvalidBarrierInterior() {
        Scene scene = Scene.diagonal(14, 7);
        scene.invalidateColumn(7);

        assertEquals(DirectionalImageSearchResult.Status.NO_ROUTE, solve(scene, false).status());
    }

    @Test
    void t042ExactAnchorsAreRetainedByConnectorEdges() {
        MetricPoint start = new MetricPoint(0.2, 0.2);
        MetricPoint end = new MetricPoint(13.7, 6.8);
        Scene scene = Scene.uniform(15, 8, start, end);
        scene.draw(List.of(start, end), 1.0);
        DirectionalImageSearchResult result = solve(scene, false);

        assertEquals(start, result.paths().get(0).points().get(0));
        assertEquals(end, result.paths().get(0).points().get(result.paths().get(0).points().size() - 1));
    }

    @Test
    void t043TrueUTurnSurvivesTheNonMonotonicImageSearch() {
        Scene scene = Scene.uniform(21, 13, new MetricPoint(1, 6), new MetricPoint(1, 1));
        scene.draw(List.of(new MetricPoint(1, 6), new MetricPoint(17, 6), new MetricPoint(17, 1),
            new MetricPoint(1, 1)), 1.0);

        List<MetricPoint> points = solve(scene, false).paths().get(0).points();
        assertTrue(points.stream().anyMatch(point -> point.xMeters() > 14.0));
        assertTrue(points.stream().anyMatch(point -> point.yMeters() < 3.0));
    }

    @Test
    void t044OrderedAnchorPhasesPreserveGlobalIntervalContinuity() {
        MetricPoint start = new MetricPoint(1, 1);
        MetricPoint middle = new MetricPoint(10, 6);
        MetricPoint end = new MetricPoint(18, 1);
        Scene scene = Scene.uniform(21, 10, start, end);
        scene.draw(List.of(start, middle, end), 1.0);

        DirectionalImageSearchProblem problem = scene.problem(List.of(start, middle, end),
            DirectionalImageSearchProblem.Scope.ORDINARY, false);
        List<MetricPoint> points = new DirectionalImageSearch().solve(problem).paths().get(0).points();
        assertTrue(points.contains(middle));
        assertFalse(DirectionalImageGeometry.hasSelfIntersection(points));
    }

    @Test
    void zeroLengthAnchorConnectorPreservesHeadingAndExportedObjectiveInBothDirections() {
        List<MetricPoint> original = List.of(new MetricPoint(1, 1), new MetricPoint(10, 6),
                new MetricPoint(18, 1));
        double[] objectives = new double[2];
        for (int direction = 0; direction < 2; direction++) {
            List<MetricPoint> anchors = direction == 0 ? original
                    : List.of(original.get(2), original.get(1), original.get(0));
            Scene scene = Scene.uniform(21, 10, anchors.get(0), anchors.get(2));
            scene.draw(original, 1.0);
            DirectionalImageSearchResult result = new DirectionalImageSearch().solve(scene.problem(anchors,
                    DirectionalImageSearchProblem.Scope.ORDINARY, false));
            ImageCostField image = ImageCostField.fromEvidence(scene.evidence(), "scalar");
            assertFalse(result.paths().isEmpty());
            for (DirectionalImagePath path : result.paths()) {
                assertEquals(recomputedObjective(path, image), path.objective(), 1e-9);
            }
            objectives[direction] = result.paths().get(0).objective();
        }
        assertEquals(objectives[0], objectives[1], 1e-9);
    }

    @Test
    void t045GeometricSelfIntersectionRejectsOrientationSpaceSimpleLoop() {
        List<MetricPoint> loop = List.of(new MetricPoint(0, 0), new MetricPoint(3, 3),
            new MetricPoint(0, 3), new MetricPoint(3, 0));

        assertTrue(DirectionalImageGeometry.hasSelfIntersection(loop));
    }

    @Test
    void t046BrighterWrongParallelRouteRemainsAmbiguousWithoutPortEvidence() {
        Scene scene = Scene.uniform(21, 12, new MetricPoint(1, 1), new MetricPoint(19, 1));
        scene.draw(List.of(new MetricPoint(1, 1), new MetricPoint(19, 1)), 0.7);
        scene.draw(List.of(new MetricPoint(1, 1), new MetricPoint(1, 8), new MetricPoint(19, 8),
            new MetricPoint(19, 1)), 1.0);

        DirectionalImageSearchResult result = solve(scene, false);
        assertEquals(DirectionalImageSearchResult.Status.AMBIGUOUS, result.status());
        assertTrue(result.paths().size() >= 2);
    }


    void cancellationIsObservedBeforeAnyPureGraphExpansion() {
        assertThrows(java.util.concurrent.CancellationException.class, () -> new DirectionalImageSearch().solve(
            Scene.straight(14, 7).problem(List.of(new MetricPoint(1, 3.5), new MetricPoint(12, 3.5)),
                DirectionalImageSearchProblem.Scope.ORDINARY, false), () -> true));
    }

    private static DirectionalImageSearchResult solve(Scene scene, boolean zeroHeuristic) {
        return new DirectionalImageSearch().solve(scene.problem(List.of(scene.start(), scene.end()),
            DirectionalImageSearchProblem.Scope.ORDINARY, zeroHeuristic));
    }

    private static double recomputedObjective(DirectionalImagePath path, ImageCostField image) {
        double result = 0.0;
        for (int index = 1; index < path.points().size(); index++) {
            MetricPoint start = path.points().get(index - 1);
            MetricPoint end = path.points().get(index);
            MetricPoint tangent = new MetricPoint(end.xMeters() - start.xMeters(),
                    end.yMeters() - start.yMeters());
            double length = start.distanceTo(end);
            int samples = Math.max(1, (int) Math.ceil(length / 0.25));
            double energy = 0.0;
            for (int sampleIndex = 0; sampleIndex <= samples; sampleIndex++) {
                double fraction = (double) sampleIndex / samples;
                MetricPoint point = new MetricPoint(start.xMeters() + fraction * tangent.xMeters(),
                        start.yMeters() + fraction * tangent.yMeters());
                energy += image.sampleRoute(point, tangent).orElseThrow().imageEnergy();
            }
            result += length * (0.05 + energy / (samples + 1.0));
            if (index > 1) {
                result += 0.25 * (1.0 - Math.cos(path.headingsRadians().get(index - 1)
                        - path.headingsRadians().get(index - 2)));
            }
        }
        return result;
    }
}
