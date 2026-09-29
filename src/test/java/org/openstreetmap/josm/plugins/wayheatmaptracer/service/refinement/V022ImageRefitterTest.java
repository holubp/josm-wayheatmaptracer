package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.ObservedModeStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.UniqueSegmentStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022SceneCatalog;

/** T053-T060: deterministic image-supported refitting and its numerical safeguards. */
class V022ImageRefitterTest {
    private final ImageSupportedRefitter refitter = new ImageSupportedRefitter();

    @Test
    void compatibilityFrozenProfilesDoNotInventObservedUniqueness() {
        FrozenProfile legacy = new FrozenProfile(FrozenSupport.MEASURED,
                new MetricPoint(0, 0), new MetricPoint(0, 1), -0.25, 0.25,
                0.5, 0.0, 1.0, new double[] {-1, 0, 1},
                new double[] {0, 1, 0}, List.of(), 0.0, 0.0);

        assertEquals(ObservedModeStatus.UNKNOWN, legacy.observedModeStatus());
    }

    @Test
    void frozenProfileCertifiesOnlyOneCompleteUncensoredObservedMode() {
        FrozenProfile unique = straightGaussianImage(false).freezeProfile(
                new MetricPoint(45, 0), new MetricPoint(1, 0));
        FrozenProfile parallel = parallelGaussianImage().freezeProfile(
                new MetricPoint(45, 0), new MetricPoint(1, 0));

        assertEquals(ObservedModeStatus.OBSERVED_UNIQUE, unique.observedModeStatus());
        assertEquals(ObservedModeStatus.OBSERVED_AMBIGUOUS, parallel.observedModeStatus());
    }

    @Test
    void directUniqueSegmentCertificateRejectsDisconnectedModeJump() {
        ImageCostField straight = straightGaussianImage(false);
        ImageCostField disconnected = disconnectedGaussianImage();

        assertEquals(UniqueSegmentStatus.DIRECT_UNIQUE,
                straight.certifyDirectUniqueSegment(new MetricPoint(10, 0),
                        new MetricPoint(20, 0), CancellationProbe.NONE));
        assertEquals(UniqueSegmentStatus.AMBIGUOUS,
                disconnected.certifyDirectUniqueSegment(new MetricPoint(10, 0),
                        new MetricPoint(20, 4), CancellationProbe.NONE));
    }

    @Test
    void uniformBrightRasterCannotAuthorizeStraighteningAnUnsupportedApex() {
        List<MetricPoint> initial = List.of(new MetricPoint(10, 20), new MetricPoint(30, 24),
                new MetricPoint(50, 20));
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, 2), uniformImage(1.0), MetricRegion.rectangle(0, 0, 60, 40),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of());

        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertEquals(ImageSupportedRefitter.Status.SKIPPED_NO_ELIGIBLE_CONTROLS, result.status());
        assertEquals(initial, result.points());
        assertFalse(result.acceptedAlternative());
    }

    @Test
    void constantDensityIntegralScalesWithPhysicalRouteLength() {
        ImageCostField image = straightGaussianImage(false);
        ImageSupportedRefitter.Config config = new ImageSupportedRefitter.Config(1.0, 0.0, 0.0,
                1.25, 150, 20, 1.0e-4, 1.0e-5, 1.0e-6, 1.0e-3, 3);
        ImageCostField.FrozenProfile frozen = image.freezeProfile(new MetricPoint(10, 0.5),
                new MetricPoint(1, 0));
        double density = frozen.positionalReliability()
                * frozen.evaluate(new MetricPoint(10, 0.5)).orElseThrow().cost();

        for (double length : List.of(20.0, 40.0)) {
            List<MetricPoint> points = List.of(new MetricPoint(10, 0.5),
                    new MetricPoint(10 + length, 0.5));
            ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(points, points,
                    Set.of(0, 1), image, MetricRegion.rectangle(0, -20, 89.5, 19.5),
                    ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, config, List.of());

            assertEquals(length * density, refitter.evaluate(refitter.freeze(request), points).objective(),
                    1.0e-10, "length=" + length);
        }
    }

    @Test
    void requestRejectsOversizedGeometryBeforeReadingItsElements() {
        List<MetricPoint> malicious = new AbstractList<>() {
            @Override public MetricPoint get(int index) {
                throw new AssertionError("oversized list was read");
            }
            @Override public int size() {
                return 32_769;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> new ImageSupportedRefitter.Request(
                malicious, malicious, Set.of(), straightGaussianImage(false),
                MetricRegion.rectangle(0, -20, 89.5, 19.5), ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of()));
    }

    @Test
    void retainedProfileBudgetRejectsBeforeAnyLargeProfileAllocation() {
        List<MetricPoint> points = List.of(new MetricPoint(10, 0), new MetricPoint(70, 0));
        ImageSupportedRefitter.Config tinyPitch = ImageSupportedRefitter.Config.defaults(1.0e-6);
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(points, points,
                Set.of(0, 1), straightGaussianImage(false), MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, tinyPitch, List.of());

        assertTrue(ImageSupportedRefitter.estimatedFrozenProfilePeakBytes(65_536, 65_536)
                > 256L * 1024L * 1024L);
        assertThrows(IllegalArgumentException.class, () -> refitter.freeze(request));
    }

    @Test
    void cancellationInterruptsFrozenSamplingAndOptimizationDeterministically() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 83);
        ImageSupportedRefitter.Request request = request(scene, points(scene),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()));
        AtomicInteger checkpoints = new AtomicInteger();

        assertThrows(CancellationException.class,
                () -> refitter.refit(request, () -> checkpoints.incrementAndGet() >= 50));
        assertEquals(50, checkpoints.get());
    }

    @Test
    void frozenOrientationInterpolatesAnOffGridImageDirection() {
        double angle = Math.toRadians(14.0);
        ImageCostField image = orientedGaussianImage(angle, false);
        MetricPoint tangent = new MetricPoint(Math.cos(angle), Math.sin(angle));

        ImageCostField.FrozenProfile profile = image.freezeProfile(new MetricPoint(45, 0), tangent);

        assertEquals(angle, profile.orientationRadians(), Math.toRadians(3.0));
        assertFalse(profile.orientationModes().isEmpty());
        assertTrue(profile.orientationCertainty() > 0.0);
    }

    @Test
    void frozenOrientationRetainsOneCircularPlateauAcrossZeroDegrees() {
        double angle = Math.toRadians(175.0);
        ImageCostField image = rotatedRasterGaussianImage(angle);
        MetricPoint tangent = new MetricPoint(Math.cos(angle), Math.sin(angle));

        ImageCostField.FrozenProfile profile = image.freezeProfile(new MetricPoint(0, 0), tangent);

        assertTrue(profile.orientationModes().stream()
                .anyMatch(org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport
                        .AngularMode::wraps));
        assertEquals(angle, profile.orientationRadians(), Math.toRadians(1.0));
    }

    @Test
    void frozenOrientationCertaintyIncludesMinimumValidRayFraction() {
        ImageCostField full = orientedGaussianImage(0.0, false);
        ImageCostField clipped = orientedGaussianImage(0.0, true);

        ImageCostField.FrozenProfile complete = full.freezeProfile(new MetricPoint(45, 0),
                new MetricPoint(1, 0));
        ImageCostField.FrozenProfile partial = clipped.freezeProfile(new MetricPoint(45, 0),
                new MetricPoint(1, 0));

        assertTrue(partial.orientationCertainty() > 0.0);
        assertTrue(partial.orientationCertainty() < complete.orientationCertainty());
    }

    @Test
    void finalReextractionRejectsBranchSwitchButAcceptsSameBranchPerturbation() {
        ImageCostField image = parallelGaussianImage();
        List<MetricPoint> initial = List.of(new MetricPoint(10, 0), new MetricPoint(70, 0));
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, 1), image, MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of());
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);

        assertTrue(refitter.finalBranchSupported(problem,
                List.of(new MetricPoint(10, 0.2), new MetricPoint(70, 0.2)), () -> false));
        assertFalse(refitter.finalBranchSupported(problem,
                List.of(new MetricPoint(10, 1.25), new MetricPoint(70, 1.25)), () -> false));
    }

    @Test
    void globalMeshPhaseIgnoresOrdinaryNonMidpointCollinearInsertionNearBend() {
        ImageCostField image = straightGaussianImage(false);
        List<MetricPoint> original = List.of(new MetricPoint(10, 0), new MetricPoint(40, 0),
                new MetricPoint(50, 10), new MetricPoint(70, 10));
        List<MetricPoint> subdivided = List.of(new MetricPoint(10, 0), new MetricPoint(39.1, 0),
                new MetricPoint(40, 0), new MetricPoint(50, 10), new MetricPoint(70, 10));

        ImageSupportedRefitter.FrozenProblem first = refitter.freeze(new ImageSupportedRefitter.Request(
                original, original, Set.of(0, original.size() - 1), image,
                MetricRegion.rectangle(0, -20, 89.5, 19.5), ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of()));
        ImageSupportedRefitter.FrozenProblem second = refitter.freeze(new ImageSupportedRefitter.Request(
                subdivided, subdivided, Set.of(0, subdivided.size() - 1), image,
                MetricRegion.rectangle(0, -20, 89.5, 19.5), ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of()));

        assertEquals(first.referenceLengthMeters(), second.referenceLengthMeters(), 1.0e-12);
        assertEquals(first.meshPointCount(), second.meshPointCount());
    }

    @Test
    void explicitProtectedNonMidpointOccurrenceRemainsExact() {
        ImageCostField image = straightGaussianImage(false);
        List<MetricPoint> initial = List.of(new MetricPoint(10, 0.5), new MetricPoint(23.1, 0.5),
                new MetricPoint(70, 0.5));
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, 1, 2), image, MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of());

        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertEquals(initial, result.points());
        assertTrue(refitter.freeze(request).frozenControlReasons().get(1)
                .contains(ImageSupportedRefitter.FreezeReason.EXPLICIT_FIXED));
    }

    @Test
    void missingInteriorMeshRowsFreezeEveryContributingControl() {
        ImageCostField image = straightGaussianImage(true);
        List<MetricPoint> initial = List.of(new MetricPoint(10, 0), new MetricPoint(20, 1),
                new MetricPoint(30, 0));
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, 2), image, MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of());

        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertTrue(problem.frozenControlIndices().contains(1));
        assertEquals(initial, result.points());
        assertEquals(ImageSupportedRefitter.Status.SKIPPED_NO_ELIGIBLE_CONTROLS, result.status());
    }

    @Test
    void missingInteriorFreezesOnlyItsLocalFootprintAndLeavesObservedIslandEligible() {
        ImageCostField image = straightGaussianImage(true);
        List<MetricPoint> initial = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new MetricPoint(index * 10.0, index == 5 ? 1.0 : 0.0)).toList();
        ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                Set.of(0, initial.size() - 1), image, MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(1.0), List.of());

        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertTrue(problem.frozenControlIndices().contains(1));
        assertTrue(problem.frozenControlReasons().get(1)
                .contains(ImageSupportedRefitter.FreezeReason.UNAVAILABLE_PROFILE));
        assertTrue(java.util.stream.IntStream.range(1, initial.size() - 1)
                .anyMatch(index -> !problem.frozenControlIndices().contains(index)),
                "observed island was erased: " + problem.frozenControlIndices());
        assertTrue(refitter.evaluate(problem, initial).supported());
        assertTrue(result.finalObjective() < result.initialObjective());
        assertTrue(result.acceptedAlternative());
        assertTrue(result.points().get(4).distanceTo(initial.get(4)) > 0.05);
        problem.frozenControlIndices().forEach(index -> assertEquals(initial.get(index),
                result.points().get(index), "frozen coordinate " + index));
        assertTrue(result.points().stream().allMatch(point -> Double.isFinite(point.xMeters())
                && Double.isFinite(point.yMeters())));
    }

    @Test
    void T053_analyticGradientAgreesWithFiniteDifferences() {
        List<MetricPoint> points = mixedMaskedPoints();
        ImageSupportedRefitter.Request request = mixedMaskedRequest(points,
                ImageSupportedRefitter.Config.defaults(1.0));
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.ObjectiveEvaluation evaluation = refitter.evaluate(problem, request.initialPoints());

        double maximumError = 0.0;
        String maximumContext = "";
        int checkedCoordinates = 0;
        double epsilon = 1.0e-5;
        for (int index = 1; index < request.initialPoints().size() - 1; index++) {
            if (problem.frozenControlIndices().contains(index)) {
                continue;
            }
            for (int axis = 0; axis < 2; axis++) {
                checkedCoordinates++;
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
        assertTrue(checkedCoordinates > 0, "gradient test requires movable image-supported controls: "
                + problem.frozenControlReasons());
        assertTrue(maximumError < 5.0e-4, "maximum gradient error=" + maximumError + maximumContext);
    }

    @Test
    void T053_imageEvidenceGradientAgreesWithoutRegularizers() {
        List<MetricPoint> points = mixedMaskedPoints();
        ImageSupportedRefitter.Config defaults = ImageSupportedRefitter.Config.defaults(1.0);
        ImageSupportedRefitter.Config imageOnly = new ImageSupportedRefitter.Config(
                defaults.sourcePitchMeters(), 0.0, 0.0, defaults.trustRadiusMeters(),
                defaults.maximumIterations(), defaults.maximumLineSearchHalvings(), defaults.armijoCoefficient(),
                defaults.gradientTolerance(), defaults.objectiveTolerance(), defaults.movementToleranceMeters(),
                defaults.stableIterationsRequired());
        ImageSupportedRefitter.Request request = mixedMaskedRequest(points, imageOnly);
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.ObjectiveEvaluation evaluation = refitter.evaluate(problem, request.initialPoints());
        double epsilon = 1.0e-5;
        assertFalse(problem.frozenControlIndices().contains(4));
        double numeric = (refitter.evaluate(problem, displaced(request.initialPoints(), 4, 1, epsilon)).objective()
                - refitter.evaluate(problem, displaced(request.initialPoints(), 4, 1, -epsilon)).objective())
                / (2.0 * epsilon);

        assertEquals(numeric, evaluation.gradient().get(4).yMeters(), 5.0e-4);
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
        ImageSupportedRefitter.Request request = request(scene, initial,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()));
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertEquals(Set.of(0, 1, 6, 7), problem.frozenControlIndices());
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
        ImageSupportedRefitter.Request request = request(scene, initial,
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                ImageSupportedRefitter.Config.defaults(scene.rasterPitchMeters()));
        ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
        ImageSupportedRefitter.ObjectiveEvaluation initialEvaluation = refitter.evaluate(problem, initial);
        long frozenInterior = problem.frozenControlIndices().stream()
                .filter(index -> index > 0 && index < initial.size() - 1).count();
        assertTrue(initialEvaluation.supported());
        assertTrue(Double.isFinite(initialEvaluation.objective()));
        assertTrue(frozenInterior > 0, "unknown apex footprint must be frozen locally");
        assertTrue(problem.frozenControlIndices().contains(2),
                "missing apex support must freeze the apex control");
        assertTrue(problem.frozenControlReasons().get(2)
                .contains(ImageSupportedRefitter.FreezeReason.UNAVAILABLE_PROFILE));
        ImageSupportedRefitter.Result result = refitter.refit(request);

        assertEquals(ImageSupportedRefitter.Status.SKIPPED_NO_ELIGIBLE_CONTROLS, result.status());
        assertTrue(result.validationCodes().contains("no-free-eligible-controls"));
        assertTrue(result.points().get(2).distanceTo(initial.get(2)) < 0.45);
        assertTrue(maximumAbsoluteY(result.points()) > 4.35);
    }

    @Test
    void observedCurvedCorridorsRemainMeasuredAndMovableAtStrongAndFaintIntensity() {
        List<MetricPoint> trueCurve = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> {
                    double x = index * 10.0;
                    return new MetricPoint(x, curvedY(x));
                }).toList();
        java.util.ArrayList<MetricPoint> disturbed = new java.util.ArrayList<>(trueCurve);
        MetricPoint wrinkle = disturbed.get(2);
        disturbed.set(2, new MetricPoint(wrinkle.xMeters(), wrinkle.yMeters() + 0.8));
        List<MetricPoint> initial = List.copyOf(disturbed);

        for (double response : List.of(0.8, 0.08)) {
            ImageSupportedRefitter.Request request = new ImageSupportedRefitter.Request(initial, initial,
                    Set.of(0, initial.size() - 1), curvedGaussianImage(response),
                    MetricRegion.rectangle(0, -20, 89.5, 19.5),
                    ImageSupportedRefitter.Mode.IMAGE_SUPPORTED,
                    ImageSupportedRefitter.Config.defaults(1.0), List.of());
            ImageSupportedRefitter.FrozenProblem problem = refitter.freeze(request);
            ImageSupportedRefitter.Result result = refitter.refit(request);

            assertTrue(problem.curvatureTargets().stream()
                    .anyMatch(target -> target.status() == SupportedCurvatureBank.Status.MEASURED),
                    "response=" + response);
            assertTrue(java.util.stream.IntStream.range(1, initial.size() - 1)
                    .anyMatch(index -> !problem.frozenControlIndices().contains(index)),
                    "response=" + response + " frozen=" + problem.frozenControlIndices());
            assertTrue(result.acceptedAlternative(), "response=" + response + " status=" + result.status());
            assertTrue(result.points().get(2).distanceTo(initial.get(2)) > 0.05,
                    "response=" + response);
            assertTrue(maximumAbsoluteY(result.points()) > 3.5, "response=" + response);
        }
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
        List<MetricPoint> initial = mixedMaskedPoints();
        ImageSupportedRefitter.Request request = mixedMaskedRequest(initial,
                ImageSupportedRefitter.Config.defaults(1.0))
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

    private static List<MetricPoint> mixedMaskedPoints() {
        return java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> new MetricPoint(index * 10.0, index == 5 ? 1.0 : 0.0)).toList();
    }

    private static ImageSupportedRefitter.Request mixedMaskedRequest(List<MetricPoint> points,
            ImageSupportedRefitter.Config config) {
        return new ImageSupportedRefitter.Request(points, points, Set.of(0, points.size() - 1),
                straightGaussianImage(true), MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedRefitter.Mode.IMAGE_SUPPORTED, config, List.of());
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

    private static ImageCostField uniformImage(double value) {
        int width = 64;
        int height = 48;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(values, value);
        java.util.Arrays.fill(valid, true);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "uniform",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        RasterMetricTransform transform = new RasterMetricTransform("uniform-positive-y-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, 0), 1.0, 0.0, 0.0, 1.0, 1.0);
        return new ImageCostField(field, transform, MetricRegion.rectangle(0, 0, 63, 47), 1.0);
    }

    private static ImageCostField straightGaussianImage(boolean maskInterior) {
        int width = 180;
        int height = 80;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = 0.5 * x;
                double metricY = -20.0 + 0.5 * y;
                int index = y * width + x;
                valid[index] = !maskInterior || metricX < 17.5 || metricX > 18.5;
                values[index] = valid[index]
                        ? 0.01 + 0.8 * Math.exp(-0.5 * metricY * metricY) : Double.NaN;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        MetricRegion region = MetricRegion.rectangle(0, -20, 89.5, 19.5);
        return new ImageCostField(field, new RasterMetricTransform("gaussian-positive-y-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -20), 0.5, 0.0, 0.0, 0.5, 1.0), region, 1.0);
    }

    private static double curvedY(double x) {
        return 4.0 * Math.sin(Math.PI * (x - 10.0) / 60.0);
    }

    private static ImageCostField curvedGaussianImage(double response) {
        int width = 180;
        int height = 80;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = 0.5 * x;
                double metricY = -20.0 + 0.5 * y;
                double distance = metricY - curvedY(metricX);
                values[y * width + x] = 0.01 + response * Math.exp(-0.5 * distance * distance);
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "curved-gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("curved-gaussian-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -20), 0.5, 0.0, 0.0, 0.5, 1.0),
                MetricRegion.rectangle(0, -20, 89.5, 19.5), 1.0);
    }

    private static ImageCostField orientedGaussianImage(double angle, boolean clipOuterRays) {
        int width = 180;
        int height = 160;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        MetricPoint center = new MetricPoint(45, 0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = 0.5 * x;
                double metricY = -40.0 + 0.5 * y;
                double dx = metricX - center.xMeters();
                double dy = metricY - center.yMeters();
                double normalDistance = -Math.sin(angle) * dx + Math.cos(angle) * dy;
                int index = y * width + x;
                valid[index] = !clipOuterRays || Math.hypot(dx, dy) <= 5.75;
                values[index] = valid[index]
                        ? 0.01 + 0.8 * Math.exp(-0.5 * normalDistance * normalDistance) : Double.NaN;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "oriented-gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        MetricRegion region = MetricRegion.rectangle(0, -40, 89.5, 39.5);
        return new ImageCostField(field, new RasterMetricTransform("oriented-gaussian-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -40), 0.5, 0.0, 0.0, 0.5, 1.0), region, 1.0);
    }

    private static ImageCostField rotatedRasterGaussianImage(double angle) {
        int size = 101;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                valid[index] = true;
                double normalDistance = y - 50.0;
                values[index] = 0.01 + 0.8 * Math.exp(-0.5 * normalDistance * normalDistance);
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "rotated-gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        MetricPoint origin = new MetricPoint(-50.0 * c + 50.0 * s, -50.0 * s - 50.0 * c);
        RasterMetricTransform transform = new RasterMetricTransform("rotated-gaussian-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, origin,
                c, s, -s, c, 1.0);
        return new ImageCostField(field, transform, MetricRegion.rectangle(-20, -20, 20, 20), 1.0);
    }

    private static ImageCostField parallelGaussianImage() {
        int width = 180;
        int height = 80;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricY = -20.0 + 0.5 * y;
                double response = Math.max(Math.exp(-0.5 * metricY * metricY / 0.16),
                        Math.exp(-0.5 * (metricY - 2.4) * (metricY - 2.4) / 0.16));
                values[y * width + x] = 0.01 + 0.8 * response;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "parallel-gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("parallel-gaussian-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -20), 0.5, 0.0, 0.0, 0.5, 1.0),
                MetricRegion.rectangle(0, -20, 89.5, 19.5), 1.0);
    }

    private static ImageCostField disconnectedGaussianImage() {
        int width = 180;
        int height = 80;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = 0.5 * x;
                double metricY = -20.0 + 0.5 * y;
                double center = metricX < 15.0 ? 0.0 : 4.0;
                double distance = metricY - center;
                values[y * width + x] = 0.01 + 0.8 * Math.exp(-0.5 * distance * distance);
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                        "disconnected-gaussian", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("disconnected-gaussian-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -20), 0.5, 0.0, 0.0, 0.5, 1.0),
                MetricRegion.rectangle(0, -20, 89.5, 19.5), 1.0);
    }
}
