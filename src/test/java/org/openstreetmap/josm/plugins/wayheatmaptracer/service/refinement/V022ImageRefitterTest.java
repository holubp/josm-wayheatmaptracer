package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022SceneCatalog;

/** T053-T060: deterministic image-supported refitting and its numerical safeguards. */
class V022ImageRefitterTest {
    private final ImageSupportedRefitter refitter = new ImageSupportedRefitter();

    @Test
    void T053_analyticGradientAgreesWithFiniteDifferences() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);
        ImageSupportedRefitter.Request request = request(scene, points(scene), ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()));
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.ObjectiveEvaluation evaluation = refitter.evaluate(problem, request.initialPoints());

        double maximumError = 0.0;
        String maximumContext = "";
        double epsilon = 1.0e-5;
        for (int index = 1; index < request.initialPoints().size() - 1; index++) {
            for (int axis = 0; axis < 2; axis++) {
                List<MetricPoint> plus = displaced(request.initialPoints(), index, axis, epsilon);
                List<MetricPoint> minus = displaced(request.initialPoints(), index, axis, -epsilon);
                double numeric = (refitter.evaluate(problem, plus).objective()
                        - refitter.evaluate(problem, minus).objective()) / (2.0 * epsilon);
                double analytic = axis == 0 ? evaluation.gradient().get(index).xMeters()
                        : evaluation.gradient().get(index).yMeters();
                double error = Math.abs(numeric - analytic);
                if (error > maximumError) {
                    maximumError = error;
                    maximumContext = " index=" + index + " axis=" + axis + " numeric=" + numeric
                            + " analytic=" + analytic;
                }
            }
        }
        assertTrue(maximumError < 5.0e-4, "maximum gradient error=" + maximumError + maximumContext);
    }

    @Test
    void T053_imageEvidenceGradientAgreesWithoutRegularizers() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);
        ImageSupportedRefitter.Config defaults = ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters());
        ImageSupportedRefitter.Config imageOnly = new ImageSupportedRefitter.Config(
                defaults.sourcePitchMeters(), 0.0, 0.0, defaults.trustRadiusMeters(),
                defaults.maximumIterations(), defaults.maximumLineSearchHalvings(), defaults.armijoCoefficient(),
                defaults.gradientTolerance(), defaults.objectiveTolerance(), defaults.movementToleranceMeters(),
                defaults.stableIterationsRequired());
        ImageSupportedRefitter.Request request = request(scene, points(scene),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, imageOnly);
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.ObjectiveEvaluation evaluation = refitter.evaluate(problem, request.initialPoints());
        double epsilon = 1.0e-5;
        double numeric = (refitter.evaluate(problem, displaced(request.initialPoints(), 2, 1, epsilon)).objective()
                - refitter.evaluate(problem, displaced(request.initialPoints(), 2, 1, -epsilon)).objective())
                / (2.0 * epsilon);

        assertEquals(numeric, evaluation.gradient().get(2).yMeters(), 5.0e-4);
    }

    @Test
    void T053_imageSamplePresenceGradientUsesMetricCoordinates() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);
        ImageCostField image = RefinementTestFixtures.image(scene);
        MetricPoint point = points(scene).get(2);
        double epsilon = 1.0e-5;
        ImageCostField.Sample sample = image.sample(point).orElseThrow();
        double plus = image.sample(new MetricPoint(point.xMeters(), point.yMeters() + epsilon)).orElseThrow()
                .presenceCost();
        double minus = image.sample(new MetricPoint(point.xMeters(), point.yMeters() - epsilon)).orElseThrow()
                .presenceCost();

        assertEquals((plus - minus) / (2.0 * epsilon), sample.presenceGradientY(), 1.0e-6);
    }

    @Test
    void T054_moderateUnsupportedDoglegMovesTowardImageCenter() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 29);
        List<MetricPoint> initial = points(scene);
        ImageSupportedRefitter.Result result = refitter.refit(request(scene, initial,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters())));

        assertTrue(result.finalObjective() < result.initialObjective());
        assertTrue(maximumAbsoluteY(result.points()) <= 0.60,
                "initial=" + maximumAbsoluteY(initial) + " final=" + maximumAbsoluteY(result.points())
                        + " status=" + result.status());
        assertTrue(result.acceptedAlternative());
    }

    @Test
    void T055_imageSupportedRealApexIsPreserved() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S05", 47);
        List<MetricPoint> initial = points(scene);
        ImageSupportedRefitter.Result result = refitter.refit(request(scene, initial,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters())));

        assertTrue(result.points().get(2).distanceTo(initial.get(2)) < 0.45);
        assertTrue(maximumAbsoluteY(result.points()) > 4.35);
    }

    @Test
    void T056_trustRadiusIsRadialAndDoesNotAccumulateAcrossPasses() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S04", 11);
        List<MetricPoint> origin = points(scene);
        ImageSupportedRefitter.Config config = ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters());
        ImageSupportedRefitter.Result first = refitter.refit(request(scene, origin, origin,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, config));
        ImageSupportedRefitter.Result second = refitter.refit(request(scene, first.points(), origin,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, config));

        double bound = config.trustRadiusMeters();
        for (int index = 0; index < origin.size(); index++) {
            assertTrue(second.points().get(index).distanceTo(origin.get(index)) <= bound + 1.0e-9);
        }
    }

    @Test
    void T057_iterationLimitIsNotReportedAsConvergence() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 83);
        ImageSupportedRefitter.Config config = ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters())
                .withIterationLimit(1);

        ImageSupportedRefitter.Result result = refitter.refit(request(scene, points(scene),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, config));

        assertEquals(ImageSupportedRefitter.Status.ITERATION_LIMIT_RETAINED, result.status());
        assertNotEquals(ImageSupportedRefitter.Status.CONVERGED, result.status());
        assertTrue(result.acceptedAlternative());
    }

    @Test
    void T058_infeasibleLineSearchRevertsWithoutCallingItSuccess() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S27", 11);
        List<MetricPoint> initial = List.of(new MetricPoint(0, 0), new MetricPoint(55, 0),
                new MetricPoint(60, 0), new MetricPoint(65, 0), new MetricPoint(120, 0));
        ImageSupportedRefitter.Request request = request(scene, initial,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()))
                .withValidator(points -> points.equals(initial)
                        ? ImageSupportedRefitter.Validation.accepted()
                        : ImageSupportedRefitter.Validation.rejected("synthetic-late-crossing"));

        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertEquals(ImageSupportedRefitter.Status.REVERTED, result.status());
        assertEquals(initial, result.points());
        assertFalse(result.acceptedAlternative());
        assertTrue(result.validationCodes().contains("synthetic-late-crossing"));
    }

    @Test
    void T059_refitterCannotHopAcrossBranchCorridor() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S08", 29);
        List<MetricPoint> initial = List.of(new MetricPoint(0, 0.6), new MetricPoint(40, 0.6),
                new MetricPoint(80, 0.6), new MetricPoint(120, 0.6));
        MetricRegion targetBranch = MetricRegion.rectangle(-1, -2.0, 121, 2.0);
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, initial.size() - 1), RefinementTestFixtures.image(scene), targetBranch,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()), List.of());

        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertTrue(result.points().stream().allMatch(point -> Math.abs(point.yMeters()) <= 2.0));
    }

    @Test
    void T060_cleanupOffAndReduceOnlyDoNotMoveCoordinates() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 47);
        List<MetricPoint> initial = points(scene);

        ImageSupportedRefitter.Result off = refitter.refit(request(scene, initial, ImageSupportedRefitter.Mode.OFF,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters())));
        ImageSupportedRefitter.Result reduceOnly = refitter.refit(request(scene, initial,
                ImageSupportedRefitter.Mode.REDUCE_POINTS_ONLY,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters())));

        assertEquals(initial, off.points());
        assertEquals(initial, reduceOnly.points());
        assertEquals(ImageSupportedRefitter.Status.SKIPPED_OFF, off.status());
        assertEquals(ImageSupportedRefitter.Status.SKIPPED_REDUCE_ONLY, reduceOnly.status());
    }

    private static ImageSupportedRefitter.Request request(SyntheticHeatmapScene scene, List<MetricPoint> points,
            ImageSupportedRefitter.Mode mode, ImageSupportedRefitter.Config config) {
        return request(scene, points, points, mode, config);
    }

    private static ImageSupportedRefitter.Request request(SyntheticHeatmapScene scene, List<MetricPoint> points,
            List<MetricPoint> trustOrigin, ImageSupportedRefitter.Mode mode, ImageSupportedRefitter.Config config) {
        return new ImageSupportedRefitter.Request(points, trustOrigin, Set.of(0, points.size() - 1),
                RefinementTestFixtures.image(scene), RefinementTestFixtures.fullRegion(scene), mode, config, List.of());
    }

    private static List<MetricPoint> points(SyntheticHeatmapScene scene) {
        return RefinementTestFixtures.metric(scene.defectiveCandidate().orElseThrow().points());
    }

    private static List<MetricPoint> displaced(List<MetricPoint> points, int index, int axis, double delta) {
        java.util.ArrayList<MetricPoint> copy = new java.util.ArrayList<>(points);
        MetricPoint point = copy.get(index);
        copy.set(index, axis == 0 ? new MetricPoint(point.xMeters() + delta, point.yMeters())
                : new MetricPoint(point.xMeters(), point.yMeters() + delta));
        return List.copyOf(copy);
    }

    private static double maximumAbsoluteY(List<MetricPoint> points) {
        return points.stream().mapToDouble(point -> Math.abs(point.yMeters())).max().orElseThrow();
    }
}
