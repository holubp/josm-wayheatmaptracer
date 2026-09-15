package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
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

        assertEquals(ImageSupportedLocalCleanup.Status.REJECTED, result.status());
        assertTrue(result.intervals().stream().anyMatch(interval ->
                interval.detail().equals("refit-validation-rejected")));
    }

    @Test
    void T062_protectedAndMissingIslandCoordinatesRemainExact() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S26", 29);
        List<MetricPoint> original = points(scene);
        ImageSupportedLocalCleanup.Result result = cleanup.clean(request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE));

        assertTrue(result.points().contains(original.get(5)));
        assertEquals(original.get(5), result.assignments().get(pointId(5)));
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

        assertEquals(ImageSupportedLocalCleanup.Status.UNCHANGED, partial.status());
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
    void independentlyEligibleMeasuredIslandsBothChangeAroundExactMissingAnchor() {
        ImageSupportedLocalCleanup.Request request = RefinementTestFixtures.twoIslandCleanupRequest();
        for (int first : List.of(0, 6)) {
            List<MetricPoint> island = request.points().subList(first, first + 5);
            ImageSupportedRefitter.Request refitRequest = new ImageSupportedRefitter.Request(
                    island, island, Set.of(0, 4), request.image(), request.branchCorridor(),
                    ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, request.refitConfig(), List.of());
            ImageSupportedRefitter.FrozenProblem problem = new ImageSupportedRefitter().freeze(refitRequest);
            assertTrue(problem.curvatureTargets().stream()
                    .anyMatch(target -> target.status() != SupportedCurvatureBank.Status.UNKNOWN));
            assertTrue(java.util.stream.IntStream.range(1, 4)
                    .anyMatch(index -> !problem.frozenControlIndices().contains(index)),
                    "island " + first + " frozen=" + problem.frozenControlReasons());
        }

        ImageSupportedLocalCleanup.Result result = cleanup.clean(request);

        assertEquals(ImageSupportedLocalCleanup.Status.PARTIALLY_CLEANED, result.status());
        assertEquals(2, result.intervals().stream()
                .filter(ImageSupportedLocalCleanup.IntervalResult::changed).count());
        assertEquals(request.points().get(5), result.assignments().get(request.occurrenceIds().get(5)));
        assertNotSame(request.originalAssignments(), result.assignments());
        for (int index = 0; index < result.points().size(); index++) {
            if (!result.occurrenceIds().get(index).equals(request.occurrenceIds().get(5))) {
                assertTrue(request.image().supports(result.points().get(index)));
            }
        }
    }

    @Test
    void T065_insertedAnchorIsSampledAtItsOwnPositionAndStaysBoundaryOnly() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S26", 11);
        ImageSupportedLocalCleanup.Request request = request(scene,
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE);

        ImageSupportedLocalCleanup.Result result = cleanup.clean(request);

        ImageSupportedLocalCleanup.IntervalResult containingAnchor = result.intervals().stream()
                .filter(interval -> interval.frozenOccurrenceIds().contains(pointId(5))).findFirst().orElseThrow();
        assertEquals(ImageSupportedLocalCleanup.IntervalDisposition.FROZEN_MISSING_EVIDENCE,
                containingAnchor.disposition());
        assertEquals(request.points().get(5), result.assignments().get(pointId(5)));
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
                multiInterval.occurrenceIds(), multiInterval.points(), multiInterval.retainedIndices(),
                multiInterval.protectedIndices(), multiInterval.image(), multiInterval.branchCorridor(),
                multiInterval.mode(),
                multiInterval.refitConfig(), multiInterval.reductionToleranceMeters(),
                multiInterval.originalAssignments(), List.of(points -> points.size() > 5
                        ? ImageSupportedRefitter.Validation.rejected("assembled-geometry-invalid")
                        : ImageSupportedRefitter.Validation.accepted()));
        ImageSupportedLocalCleanup.Result rejected = cleanup.clean(guarded);
        assertEquals(ImageSupportedLocalCleanup.Status.REJECTED, rejected.status());
        assertEquals(multiInterval.points(), rejected.points());
    }

    @Test
    void cleanupRejectsMissingOriginalOccurrenceAssignments() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 29);
        ImageSupportedLocalCleanup.Request valid = request(scene,
                ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY);

        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedLocalCleanup.Request(
                valid.occurrenceIds(), valid.points(), valid.retainedIndices(),
                valid.protectedIndices(), valid.image(), valid.branchCorridor(), valid.mode(), valid.refitConfig(),
                valid.reductionToleranceMeters(), java.util.Map.of(), valid.validators()));
    }

    @Test
    void cleanupRejectsReorderedAndDuplicateOccurrenceAssignments() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 29);
        ImageSupportedLocalCleanup.Request valid = request(scene,
                ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY);
        java.util.Map<FinalRoutePointId, MetricPoint> reordered = new java.util.LinkedHashMap<>();
        for (int index = 0; index < valid.occurrenceIds().size(); index++) {
            int source = index == 0 ? 1 : index == 1 ? 0 : index;
            reordered.put(valid.occurrenceIds().get(index), valid.points().get(source));
        }
        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedLocalCleanup.Request(
                valid.occurrenceIds(), valid.points(), valid.retainedIndices(),
                valid.protectedIndices(), valid.image(), valid.branchCorridor(), valid.mode(), valid.refitConfig(),
                valid.reductionToleranceMeters(), reordered, valid.validators()));

        List<FinalRoutePointId> duplicate = new ArrayList<>(valid.occurrenceIds());
        duplicate.set(1, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedLocalCleanup.Request(
                duplicate, valid.points(), valid.retainedIndices(), valid.protectedIndices(),
                valid.image(), valid.branchCorridor(), valid.mode(), valid.refitConfig(),
                valid.reductionToleranceMeters(), valid.originalAssignments(), valid.validators()));
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
        List<FinalRoutePointId> ids = new ArrayList<>();
        java.util.Map<FinalRoutePointId, MetricPoint> assignments = new java.util.LinkedHashMap<>();
        for (int index = 0; index < points.size(); index++) {
            FinalRoutePointId id = pointId(index);
            ids.add(id);
            assignments.put(id, points.get(index));
        }
        return new ImageSupportedLocalCleanup.Request(ids, points, protectedIndices, protectedIndices,
                RefinementTestFixtures.image(scene), RefinementTestFixtures.fullRegion(scene), mode,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()), 0.45,
                assignments, List.of());
    }


    @Test
    void independentProbeTypedSourceOccurrenceOrderCannotBeReversedWithAlignedMap() {
        var valid = request(V022SceneCatalog.scene("S03", 29), ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY);
        var ids = new ArrayList<FinalRoutePointId>(valid.occurrenceIds());
        var way = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.WAY, 100);
        ids.set(1, new FinalRoutePointId.ExistingWayNodeOccurrence(way,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, 102), 2));
        ids.set(2, new FinalRoutePointId.ExistingWayNodeOccurrence(way,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, 101), 1));
        var assignments = new java.util.LinkedHashMap<FinalRoutePointId, MetricPoint>();
        for (int i=0; i<ids.size(); i++) assignments.put(ids.get(i), valid.points().get(i));
        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedLocalCleanup.Request(ids,
                valid.points(), valid.retainedIndices(), valid.protectedIndices(), valid.image(),
                valid.branchCorridor(), valid.mode(), valid.refitConfig(), valid.reductionToleranceMeters(),
                assignments, valid.validators()));
    }

    @Test
    void typedExistingOccurrenceSlotCannotNameTwoNodes() {
        var valid = request(V022SceneCatalog.scene("S03", 29),
                ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY);
        var ids = new ArrayList<FinalRoutePointId>(valid.occurrenceIds());
        var way = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.WAY, 100);
        ids.set(1, new FinalRoutePointId.ExistingWayNodeOccurrence(way,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, 101), 1));
        ids.set(2, new FinalRoutePointId.ExistingWayNodeOccurrence(way,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, 102), 1));

        assertInvalidTypedOccurrences(valid, ids);
    }

    @Test
    void typedExistingOccurrenceCannotRepeatOneNodeAtDifferentIndexes() {
        var valid = request(V022SceneCatalog.scene("S03", 29),
                ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY);
        var ids = new ArrayList<FinalRoutePointId>(valid.occurrenceIds());
        var way = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.WAY, 100);
        var node = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.existing(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, 101);
        ids.set(1, new FinalRoutePointId.ExistingWayNodeOccurrence(way, node, 1));
        ids.set(2, new FinalRoutePointId.ExistingWayNodeOccurrence(way, node, 2));

        assertInvalidTypedOccurrences(valid, ids);
    }

    private static void assertInvalidTypedOccurrences(ImageSupportedLocalCleanup.Request valid,
            List<FinalRoutePointId> ids) {
        var assignments = new java.util.LinkedHashMap<FinalRoutePointId, MetricPoint>();
        for (int index = 0; index < ids.size(); index++) {
            assignments.put(ids.get(index), valid.points().get(index));
        }
        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedLocalCleanup.Request(ids,
                valid.points(), valid.retainedIndices(), valid.protectedIndices(), valid.image(),
                valid.branchCorridor(), valid.mode(), valid.refitConfig(),
                valid.reductionToleranceMeters(), assignments, valid.validators()));
    }

    private static FinalRoutePointId pointId(int index) {
        return new GeneratedCandidatePoint("cleanup-fixture", index);
    }

    private static List<MetricPoint> points(SyntheticHeatmapScene scene) {
        return RefinementTestFixtures.metric(scene.defectiveCandidate().orElseThrow().points());
    }
}
