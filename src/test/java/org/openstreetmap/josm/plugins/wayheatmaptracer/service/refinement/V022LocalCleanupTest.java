package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022SceneCatalog;

/** T061-T066: independent local cleanup intervals and truthful outcome accounting. */
class V022LocalCleanupTest {
    private final ImageSupportedLocalCleanup cleanup = new ImageSupportedLocalCleanup();

    @Test
    void T061_incompleteWholeRouteStillCleansIndependentValidIslands() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S26", 11);
        ImageSupportedLocalCleanup.Result result = cleanup.clean(request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE));

        assertEquals(ImageSupportedLocalCleanup.Status.PARTIALLY_CLEANED, result.status());
        assertTrue(result.intervals().stream().filter(ImageSupportedLocalCleanup.IntervalResult::changed).count() >= 2);
    }

    @Test
    void T062_protectedAndMissingIslandCoordinatesRemainExact() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S26", 29);
        List<MetricPoint> original = points(scene);
        ImageSupportedLocalCleanup.Result result = cleanup.clean(request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE));

        assertTrue(result.points().contains(original.get(5)));
        assertEquals(original.get(5), result.assignments().get("p5"));
    }

    @Test
    void T063_reduceOnlyRetainsExactCoordinatesOfEverySurvivingOccurrence() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 47);
        List<MetricPoint> original = points(scene);
        ImageSupportedLocalCleanup.Result result = cleanup.clean(request(scene,
                ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY));

        assertTrue(original.containsAll(result.points()));
        assertFalse(result.points().isEmpty());

        ImageSupportedLocalCleanup.Result guarded = cleanup.clean(
                RefinementTestFixtures.uCorridorReductionRequest());
        assertTrue(guarded.points().size() > 2,
                "reduction must not replace a U-shaped branch interval with an outside chord");
    }

    @Test
    void T064_outcomesDistinguishPartialSkippedAndUnchanged() {
        SyntheticHeatmapScene partialScene = V022SceneCatalog.scene("S26", 83);
        ImageSupportedLocalCleanup.Result partial = cleanup.clean(request(partialScene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE));
        ImageSupportedLocalCleanup.Result skipped = cleanup.clean(request(partialScene,
                ImageSupportedLocalCleanup.Mode.OFF));
        SyntheticHeatmapScene unchangedScene = V022SceneCatalog.scene("S01", 83);
        List<MetricPoint> straight = List.of(new MetricPoint(0, 0), new MetricPoint(60, 0),
                new MetricPoint(120, 0));
        ImageSupportedLocalCleanup.Request unchangedRequest = request(unchangedScene, straight,
                Set.of(0, 1, 2), ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE);
        ImageSupportedLocalCleanup.Result unchanged = cleanup.clean(unchangedRequest);
        SyntheticHeatmapScene emptyScene = V022SceneCatalog.scene("S28", 83);
        ImageSupportedLocalCleanup.Result noEligibleInterval = cleanup.clean(request(emptyScene, straight,
                Set.of(0, 2), ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE));

        assertEquals(ImageSupportedLocalCleanup.Status.PARTIALLY_CLEANED, partial.status());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED, skipped.status());
        assertEquals(ImageSupportedLocalCleanup.Status.UNCHANGED, unchanged.status());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED, noEligibleInterval.status());

        ImageSupportedLocalCleanup.Result limited = cleanup.clean(
                RefinementTestFixtures.unlocalizedLimitedCleanupRequest());
        assertNotEquals(ImageSupportedLocalCleanup.Status.CLEANED, limited.status(),
                "an iteration-limited refit must not become ordinary cleaned success");

        ImageSupportedLocalCleanup.Result invalidOff = cleanup.clean(
                RefinementTestFixtures.cleanupOffRejectedByValidatorRequest());
        assertEquals(ImageSupportedLocalCleanup.Status.REJECTED, invalidOff.status(),
                "cleanup Off suppresses transformation, not final validation");
    }

    @Test
    void T065_insertedAnchorIsSampledAtItsOwnPositionAndStaysBoundaryOnly() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S26", 11);
        ImageSupportedLocalCleanup.Request request = request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE);

        ImageSupportedLocalCleanup.Result result = cleanup.clean(request);

        ImageSupportedLocalCleanup.IntervalResult containingAnchor = result.intervals().stream()
                .filter(interval -> interval.frozenOccurrenceIds().contains("p5")).findFirst().orElseThrow();
        assertEquals(ImageSupportedLocalCleanup.IntervalDisposition.FROZEN_MISSING_EVIDENCE,
                containingAnchor.disposition());
        assertEquals(request.points().get(5), result.assignments().get("p5"));
    }

    @Test
    void T066_assignmentsAreFreshAndMatchReducedGeometry() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 29);
        ImageSupportedLocalCleanup.Request request = request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE);

        ImageSupportedLocalCleanup.Result result = cleanup.clean(request);

        assertNotSame(request.originalAssignments(), result.assignments());
        assertEquals(result.points().size(), result.assignments().size());
        assertTrue(result.assignments().values().containsAll(result.points()));

        ImageSupportedLocalCleanup.Request multiInterval = request(V022SceneCatalog.scene("S26", 47),
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE);
        ImageSupportedLocalCleanup.Request guarded = new ImageSupportedLocalCleanup.Request(
                multiInterval.occurrenceIds(), multiInterval.points(), multiInterval.protectedIndices(),
                multiInterval.image(), multiInterval.branchCorridor(), multiInterval.mode(),
                multiInterval.refitConfig(), multiInterval.reductionToleranceMeters(),
                multiInterval.originalAssignments(), List.of(points -> points.size() > 5
                        ? ImageSupportedRefitter.Validation.rejected("assembled-geometry-invalid")
                        : ImageSupportedRefitter.Validation.accepted()));
        ImageSupportedLocalCleanup.Result rejected = cleanup.clean(guarded);
        assertEquals(ImageSupportedLocalCleanup.Status.REJECTED, rejected.status());
        assertEquals(multiInterval.points(), rejected.points());
    }

    private static ImageSupportedLocalCleanup.Request request(SyntheticHeatmapScene scene,
            ImageSupportedLocalCleanup.Mode mode) {
        List<MetricPoint> points = points(scene);
        Set<Integer> protectedIndices = scene.id().equals("S26") ? Set.of(0, 5, points.size() - 1)
                : Set.of(0, points.size() - 1);
        return request(scene, points, protectedIndices, mode);
    }

    private static ImageSupportedLocalCleanup.Request request(SyntheticHeatmapScene scene,
            List<MetricPoint> points, Set<Integer> protectedIndices, ImageSupportedLocalCleanup.Mode mode) {
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            ids.add("p" + index);
        }
        return new ImageSupportedLocalCleanup.Request(ids, points, protectedIndices,
                RefinementTestFixtures.image(scene), RefinementTestFixtures.fullRegion(scene), mode,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()), 0.45,
                java.util.Map.of("sentinel", new MetricPoint(-1, -1)), List.of());
    }

    private static List<MetricPoint> points(SyntheticHeatmapScene scene) {
        return RefinementTestFixtures.metric(scene.defectiveCandidate().orElseThrow().points());
    }
}
