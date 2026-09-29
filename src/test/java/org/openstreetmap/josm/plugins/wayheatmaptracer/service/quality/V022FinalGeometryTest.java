package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleBiFunction;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.UniqueSegmentStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022SceneCatalog;

/** T067-T074: whole final-preview geometry defects and supported-bend controls. */
class V022FinalGeometryTest {
    private final FinalGeometryEvaluator evaluator = new FinalGeometryEvaluator();

    @Test
    void T067_isolatedDoglegNeedsNoFourReversalPattern() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);
        FinalGeometryEvaluator.Result result = evaluate(scene, points(scene), false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, result.disposition());

        FinalGeometryEvaluator.Result unlocalized = QualityTestFixtures.constantFieldDogleg(evaluator);
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, unlocalized.disposition());
        assertTrue(unlocalized.has(FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT));
    }

    @Test
    void unavailableRasterGapIsTypedAndLeavesOnlyReviewRequiredSharpBend() {
        FinalGeometryEvaluator.Result shortGap = evaluator.evaluate(new FinalGeometryEvaluator.Request(
                "short-unavailable-gap", List.of(p(0, 0), p(99, 10), p(101, 0), p(200, 0)),
                narrowUnavailableGapImage(), 0.1, Set.of(), List.of(),
                false, false, false, false));

        assertTrue(shortGap.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY));
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, shortGap.disposition());
        assertFalse(shortGap.has(FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT));

        assertThrows(IllegalArgumentException.class, () -> new FinalGeometryEvaluator.Result(
                "untyped-infinity", FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, List.of(),
                10.0, 10.0, 0.0, Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new FinalGeometryEvaluator.Result(
                "fabricated-unavailable", FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,
                List.of(new FinalGeometryEvaluator.Finding(
                        FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY,
                        FinalGeometryEvaluator.Severity.REVIEW, 0, 1, 0.0)),
                10.0, 10.0, 0.0, 0.0, 0.0));
    }

    @Test
    void T068_cleanedLabelCannotBypassTheSameDefect() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S04", 29);
        FinalGeometryEvaluator.Result raw = evaluate(scene, points(scene), false);
        FinalGeometryEvaluator.Result cleaned = evaluate(scene, points(scene), true);

        assertEquals(raw.disposition(), cleaned.disposition());
        assertEquals(raw.findings(), cleaned.findings());
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, cleaned.disposition());
    }

    @Test
    void protectedAssignmentMismatchIsHardBlockedRegardlessOfCleanedLabel() {
        List<MetricPoint> moved = List.of(p(0, 0), p(10, 1), p(20, 0));
        Map<Integer, MetricPoint> expected = Map.of(0, p(0, 0), 1, p(10, 0), 2, p(20, 0));

        FinalGeometryEvaluator.Result raw = evaluator.evaluate(new FinalGeometryEvaluator.Request(
                "protected-raw", moved, QualityTestFixtures.constantImage(0.8), 1.0,
                expected, List.of(), false, false, false, false));
        FinalGeometryEvaluator.Result cleaned = evaluator.evaluate(new FinalGeometryEvaluator.Request(
                "protected-cleaned", moved, QualityTestFixtures.constantImage(0.8), 1.0,
                expected, List.of(), true, false, false, false));

        assertTrue(raw.has(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH));
        assertTrue(cleaned.has(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, raw.disposition());
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, cleaned.disposition());
    }

    @Test
    void T069_terminalOvershootIsReportedLocally() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 47);
        List<MetricPoint> geometry = List.of(p(0, 0), p(70, 0), p(105, 0), p(96, 0));

        FinalGeometryEvaluator.Result result = evaluate(scene, geometry, false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.TERMINAL_OVERSHOOT));
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void T070_nonadjacentVertexTouchIsHardBlocked() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 83);
        List<MetricPoint> geometry = List.of(p(0, 0), p(20, 0), p(20, 20), p(0, 20), p(0, 0), p(40, 0));

        FinalGeometryEvaluator.Result result = evaluate(scene, geometry, false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, result.disposition());

        FinalGeometryEvaluator.Result overlapAtJunction = QualityTestFixtures.incidentOverlap(evaluator);
        assertTrue(overlapAtJunction.has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, overlapAtJunction.disposition());
    }

    @Test
    void exactSharedEndpointTouchIsTheOnlyIncidentCrossingWaiver() {
        List<MetricPoint> candidate = List.of(p(0, 0), p(10, 0), p(20, 0));
        List<MetricPoint> incident = List.of(p(0, 0), p(0, 10));

        FinalGeometryEvaluator.Result result = evaluator.evaluate(new FinalGeometryEvaluator.Request(
                "single-shared-endpoint", candidate, QualityTestFixtures.constantImage(0.8), 1.0,
                Set.of(0, 2), List.of(incident), false, false, false, false));

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING));
    }

    @Test
    void T071_collinearOverlapIsHardBlocked() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 11);
        List<MetricPoint> geometry = List.of(p(0, 0), p(30, 0), p(10, 0), p(40, 0));

        FinalGeometryEvaluator.Result result = evaluate(scene, geometry, false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.COLLINEAR_OVERLAP));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, result.disposition());
    }

    @Test
    void T072_adjacentBacktrackIsHardBlocked() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 29);
        List<MetricPoint> geometry = List.of(p(0, 0), p(30, 0), p(12, 0), p(50, 0));

        FinalGeometryEvaluator.Result result = evaluate(scene, geometry, false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.ADJACENT_BACKTRACK));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, result.disposition());
    }

    @Test
    void T073_originalVertexTinyLoopSurvivesUniformSamplingCheck() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S01", 47);
        List<MetricPoint> geometry = List.of(p(0, 0), p(10, 0), p(10.3, 0.3), p(9.7, -0.3),
                p(10.3, -0.3), p(9.7, 0.3), p(20, 0));

        FinalGeometryEvaluator.Result result = evaluate(scene, geometry, false);

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.SELF_INTERSECTION));
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, result.disposition());
    }

    @Test
    void directedRouteCostMemoReplaysTheExactPolylineCost() {
        ImageCostField image = QualityTestFixtures.constantImage(0.8);
        List<MetricPoint> route = List.of(p(-10, 0), p(-4, 1), p(2, -1), p(8, 0));
        FinalGeometryEvaluator.DirectedSamplingMemo memo =
            new FinalGeometryEvaluator.DirectedSamplingMemo(8);

        assertEquals(image.meanRoutePolylineCost(route), memo.routePolylineCost(route, image), 0.0);
        assertEquals(image.meanRoutePolylineCost(route), memo.routePolylineCost(route, image), 0.0);
    }

    @Test
    void denseOverlappingWindowsRetainOrderedSupportAndFindings() {
        List<MetricPoint> geometry = List.of(p(0, 0), p(4, 0), p(8, 1.2), p(12, 0),
            p(16, 1.2), p(20, 0), p(24, 1.2), p(28, 0), p(32, 1.2), p(36, 0));
        FinalGeometryEvaluator.Request request = new FinalGeometryEvaluator.Request(
            "dense-overlap", geometry, QualityTestFixtures.constantImage(0.1), 0.5,
            Set.of(0, geometry.size() - 1), List.of(), false, false, false, false);

        FinalGeometryEvaluator.Result first = evaluator.evaluate(request);
        FinalGeometryEvaluator.Result second = evaluator.evaluate(request);

        assertEquals(first, second);
        assertTrue(first.has(FinalGeometryEvaluator.FindingCode.INSUFFICIENT_DIRECT_SUPPORT));
        assertTrue(first.has(FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
    }

    @Test
    void T074_genuineSharpImageSupportedBendIsPreserved() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S05", 83);
        FinalGeometryEvaluator.Result result = evaluate(scene, points(scene), false);

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.UNSUPPORTED_TERMINAL_KINK));
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, result.disposition());
    }

    @Test
    void repeatedShortWaveWarningIsLocalAndReportsMeasuredMeters() {
        List<MetricPoint> geometry = repeatedWave();
        ImageCostField straight = ridgeImage((x, y) -> gaussian(y), (x, y) -> true);
        double supportedWindowLength = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToDouble(index -> geometry.get(index - 1).distanceTo(geometry.get(index))).sum();

        assertEquals(20.0, supportedWindowLength, 1.0e-12);
        assertEquals(UniqueSegmentStatus.DIRECT_UNIQUE, straight.certifyDirectUniqueSegment(
                geometry.get(0), geometry.get(6), CancellationProbe.NONE));
        FinalGeometryEvaluator.Result result = evaluate("unsupported-wave", geometry, straight, 1.0);
        FinalGeometryEvaluator.Finding finding = result.findings().stream()
                .filter(value -> value.code()
                        == FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE)
                .findFirst().orElseThrow();

        assertEquals(FinalGeometryEvaluator.Severity.REVIEW, finding.severity());
        assertTrue(finding.firstVertex() > 0 || finding.lastVertex() < geometry.size() - 1,
                "the warning must describe one local physical window");
        assertTrue(finding.amplitudeMeters() > 0.75);
        assertTrue(finding.amplitudeMeters() < 3.0,
                "amplitudeMeters must contain a measured displacement, not a reversal count");
    }

    @Test
    void sameShortShapeOnItsObservedUniqueRidgeIsPreserved() {
        List<MetricPoint> geometry = repeatedWave();
        FinalGeometryEvaluator.Result result = evaluate("supported-wave", geometry,
                graphRidgeImage(geometry), 1.0);

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY),
                result.findings().toString());
    }

    @Test
    void supportedWaveDoesNotHideDisjointContradictedWave() {
        List<MetricPoint> geometry = twoRegionWave();
        ImageCostField image = ridgeImage((x, y) -> {
            double center = x <= 32.0
                    ? 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 10.0) : 0.0;
            return gaussian(y - center);
        }, (x, y) -> true);

        FinalGeometryEvaluator.Result result = evaluate("two-regions", geometry, image, 1.0);
        FinalGeometryEvaluator.Finding finding = wrinkle(result, "two-regions");

        assertEquals(1L, result.findings().stream().filter(value -> value.code()
                == FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE).count());
        assertTrue(finding.firstVertex() > 30, result.findings().toString());
        assertTrue(finding.lastVertex() < geometry.size(), result.findings().toString());
    }

    @Test
    void supportedBroaderOverlapDoesNotHideContradictedNarrowerWindow() {
        List<MetricPoint> geometry = denseShortWave();
        ImageCostField image = ridgeImage((x, y) -> {
            double center = x <= 7.0
                    ? 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 4.0) : 0.0;
            return gaussian(y - center);
        }, (x, y) -> true);

        FinalGeometryEvaluator.Result result = evaluate("overlapping-evidence", geometry, image, 1.0);
        FinalGeometryEvaluator.Finding finding = wrinkle(result, "overlapping-evidence");

        assertTrue(geometry.get(finding.firstVertex()).xMeters() > 7.0,
                result.findings().toString());
        assertEquals(1L, result.findings().stream().filter(value -> value.code()
                == FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE).count());
    }

    @Test
    void offGridProtectedAnchorRemainsExactInComparatorChain() {
        List<MetricPoint> geometry = new java.util.ArrayList<>(repeatedWave());
        MetricPoint protectedPoint = interpolateForTest(geometry.get(3), geometry.get(4), 0.37);
        geometry.add(4, protectedPoint);
        FinalGeometryEvaluator.Request request = new FinalGeometryEvaluator.Request(
                "off-grid-protected", geometry,
                ridgeImage((x, y) -> gaussian(y), (x, y) -> true), 1.0,
                Set.of(0, 4, geometry.size() - 1), List.of(), false, false, false, false);

        FinalGeometryEvaluator.Result result = evaluator.evaluate(request, CancellationProbe.NONE);

        assertEquals(protectedPoint, request.points().get(4));
        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY),
                result.findings().toString());
    }

    @Test
    void disconnectedUniqueModesCannotCertifyLongitudinalAlternative() {
        List<MetricPoint> geometry = repeatedWave().stream()
                .map(point -> p(point.xMeters(), point.yMeters() + 5.0)).toList();
        ImageCostField disconnected = ridgeImage((x, y) -> gaussian(y - (x < 9.25 ? 2.0 : 8.0)),
                (x, y) -> true);

        FinalGeometryEvaluator.Result result = evaluate("disconnected-unique", geometry,
                disconnected, 1.0);

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY),
                result.findings().toString());
    }

    @Test
    void offRidgeLocalWindowEndpointsCannotCertifyTranslatedAlternative() {
        List<MetricPoint> geometry = repeatedWave().stream()
                .map(point -> p(point.xMeters(), point.yMeters() + 5.0)).toList();

        FinalGeometryEvaluator.Result result = evaluate("off-ridge-window-boundaries", geometry,
                ridgeImage((x, y) -> gaussian(y), (x, y) -> true), 1.0);

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY),
                result.findings().toString());
    }

    @Test
    void separatedSupportedBendsDoNotAccumulateIntoOneWrinkle() {
        List<MetricPoint> geometry = List.of(p(5, 0), p(15, 5), p(25, 10), p(35, 5), p(45, 0),
                p(60, -5), p(75, -10), p(90, -5), p(105, 0), p(120, 5), p(135, 10));
        FinalGeometryEvaluator.Result result = evaluate("supported-bends", geometry,
                polylineRidgeImage(geometry), 1.0);

        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
    }

    @Test
    void subOnsetNoiseAndSourcePitchScalingDoNotCreateWrinkle() {
        List<MetricPoint> small = List.of(p(5, 0), p(8, 0.45), p(11, -0.45), p(14, 0.45),
                p(17, -0.45), p(20, 0));
        List<MetricPoint> pitchScaled = List.of(p(5, 0), p(8, 1.1), p(11, -1.1), p(14, 1.1),
                p(17, -1.1), p(20, 0));

        assertFalse(evaluate("sub-onset", small,
                ridgeImage((x, y) -> gaussian(y), (x, y) -> true), 1.0)
                .has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertFalse(evaluate("pitch-scaled", pitchScaled,
                ridgeImage((x, y) -> gaussian(y / 3.0), (x, y) -> true), 3.0)
                .has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
    }

    @Test
    void parallelAndUnavailableModesProduceTypedLocalAmbiguity() {
        List<MetricPoint> geometry = repeatedWave();
        ImageCostField parallel = ridgeImage((x, y) -> Math.max(gaussian(y - 2.0),
                gaussian(y + 2.0)), (x, y) -> true);
        ImageCostField unavailable = ridgeImage((x, y) -> gaussian(y),
                (x, y) -> x < 9.0 || x > 14.0);

        for (FinalGeometryEvaluator.Result result : List.of(
                evaluate("parallel", geometry, parallel, 1.0),
                evaluate("unavailable", geometry, unavailable, 1.0))) {
            assertFalse(result.has(FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
            assertTrue(result.has(FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY));
            assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, result.disposition());
        }
    }

    @Test
    void repeatedWaveDecisionIsInvariantToSubdivisionReflectionAndReversal() {
        List<MetricPoint> geometry = repeatedWave();
        List<MetricPoint> subdivided = new java.util.ArrayList<>(geometry);
        subdivided.add(4, interpolateForTest(geometry.get(3), geometry.get(4), 0.37));
        List<MetricPoint> reflected = geometry.stream().map(point -> p(point.xMeters(),
                -point.yMeters())).toList();
        List<MetricPoint> reversed = new java.util.ArrayList<>(geometry);
        java.util.Collections.reverse(reversed);
        ImageCostField straight = ridgeImage((x, y) -> gaussian(y), (x, y) -> true);

        double expected = wrinkle(evaluate("base", geometry, straight, 1.0)).amplitudeMeters();
        assertEquals(expected, wrinkle(evaluate("subdivided", subdivided, straight, 1.0),
                "subdivided").amplitudeMeters(), 1.0e-9);
        assertEquals(expected, wrinkle(evaluate("reflected", reflected, straight, 1.0),
                "reflected").amplitudeMeters(), 1.0e-9);
        assertEquals(expected, wrinkle(evaluate("reversed", reversed, straight, 1.0),
                "reversed").amplitudeMeters(), 1.0e-9);
    }

    @Test
    void localWarningSamplingPollsCancellation() {
        AtomicInteger checkpoints = new AtomicInteger();
        FinalGeometryEvaluator.Request request = request("cancelled", repeatedWave(),
                ridgeImage((x, y) -> gaussian(y), (x, y) -> true), 1.0);

        assertThrows(CancellationException.class, () -> evaluator.evaluate(request,
                () -> checkpoints.incrementAndGet() >= 5));
        assertEquals(5, checkpoints.get());
    }

    @Test
    void longRouteUsesIndexedWindowsWithoutRetainingTheWholePhysicalGrid() {
        AtomicInteger checkpoints = new AtomicInteger();
        FinalGeometryEvaluator.Request request = request("long-route",
                List.of(p(0, 0), p(10_000, 0)), QualityTestFixtures.constantImage(0.8), 0.25);

        assertDoesNotThrow(() -> evaluator.evaluate(request, () -> {
            checkpoints.incrementAndGet();
            return false;
        }));
        assertTrue(checkpoints.get() < 2_000_000,
                "physical-window work must stay proportional to route samples");
    }

    @Test
    void separatedPacketsUseLinearCandidateComparisonOperations() {
        List<MetricPoint> geometry = separatedWavePackets(100);
        FinalGeometryEvaluator.LocalWarningInspection inspection = evaluator
                .inspectRepeatedShortWavesForTest(request("long-wave-packets", geometry,
                        wideStraightRidgeImage(10_020), 1.0), CancellationProbe.NONE);

        assertTrue(inspection.stats().qualifyingWindows() > 100);
        assertEquals(inspection.stats().qualifyingWindows(),
                inspection.stats().candidateComparisons());
        double physicalLength = java.util.stream.IntStream.range(1, geometry.size())
                .mapToDouble(index -> geometry.get(index - 1).distanceTo(geometry.get(index))).sum();
        long physicalSampleRows = (long) Math.ceil(physicalLength / 0.5) + 1L;
        assertTrue(inspection.stats().windowVisits() <= 3L * physicalSampleRows,
                inspection.stats().toString());
        assertTrue(inspection.stats().candidateComparisons()
                <= inspection.stats().windowVisits(), inspection.stats().toString());
    }

    @Test
    void denseOriginalVerticesAbstainWithinConcreteRowAndProfileBudgets() {
        int count = 80_000;
        List<MetricPoint> geometry = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    double x = 2.0 + 20.0 * index / (count - 1.0);
                    return p(x, 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 12.0));
                }).toList();
        FinalGeometryEvaluator.LocalWarningInspection inspection = evaluator
                .inspectRepeatedShortWavesForTest(request("dense-originals", geometry,
                        ridgeImage((x, y) -> gaussian(y
                                - 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 12.0)),
                                (x, y) -> true), 1.0), CancellationProbe.NONE);

        assertFalse(inspection.findings().stream().anyMatch(finding -> finding.code()
                == FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE));
        assertTrue(inspection.findings().stream().anyMatch(finding -> finding.code()
                == FinalGeometryEvaluator.FindingCode.LOCAL_SHAPE_IMAGE_AMBIGUITY));
        assertTrue(inspection.stats().maximumDiagnosticRows() <= 65_536,
                inspection.stats().toString());
        assertTrue(inspection.stats().maximumCachedProfileRows() <= 65_536,
                inspection.stats().toString());
        assertTrue(inspection.stats().rowBudgetAbstentions() > 0,
                inspection.stats().toString());
    }

    private FinalGeometryEvaluator.Result evaluate(SyntheticHeatmapScene scene, List<MetricPoint> geometry,
            boolean cleaned) {
        return evaluator.evaluate(new FinalGeometryEvaluator.Request("candidate", geometry,
                QualityTestFixtures.image(scene), scene.rasterPitchMeters(), Set.of(0, geometry.size() - 1),
                List.of(), cleaned, false, false, false));
    }

    private FinalGeometryEvaluator.Result evaluate(String id, List<MetricPoint> geometry,
            ImageCostField image, double pitch) {
        return evaluator.evaluate(request(id, geometry, image, pitch), CancellationProbe.NONE);
    }

    private static FinalGeometryEvaluator.Request request(String id, List<MetricPoint> geometry,
            ImageCostField image, double pitch) {
        return new FinalGeometryEvaluator.Request(id, geometry, image, pitch,
                Set.of(0, geometry.size() - 1), List.of(), false, false, false, false);
    }

    private static FinalGeometryEvaluator.Finding wrinkle(FinalGeometryEvaluator.Result result) {
        return wrinkle(result, result.id());
    }

    private static FinalGeometryEvaluator.Finding wrinkle(FinalGeometryEvaluator.Result result,
            String label) {
        return result.findings().stream().filter(value -> value.code()
                == FinalGeometryEvaluator.FindingCode.REPEATED_SHORT_WAVE_WRINKLE)
                .findFirst().orElseThrow(() -> new AssertionError(label + ": " + result.findings()));
    }

    private static List<MetricPoint> repeatedWave() {
        List<MetricPoint> result = new java.util.ArrayList<>();
        double x = 0.0;
        double previousY = 0.0;
        result.add(p(x, previousY));
        x += 5.0;
        result.add(p(x, previousY));
        for (double y : new double[] {1.1, -1.1, 1.1, -1.1, 0.0}) {
            double dy = y - previousY;
            x += Math.sqrt(9.0 - dy * dy);
            result.add(p(x, y));
            previousY = y;
        }
        result.add(p(x + 10.0, 0.0));
        return List.copyOf(result);
    }

    private static List<MetricPoint> twoRegionWave() {
        return java.util.stream.IntStream.rangeClosed(2, 70).mapToObj(x -> {
            double y = x <= 32
                    ? 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 10.0)
                    : x >= 42 && x <= 66
                            ? 1.4 * Math.sin(2.0 * Math.PI * (x - 42.0) / 12.0) : 0.0;
            return p(x, y);
        }).toList();
    }

    private static List<MetricPoint> denseShortWave() {
        return java.util.stream.IntStream.rangeClosed(0, 56)
                .mapToObj(index -> {
                    double x = 2.0 + index * 0.5;
                    return p(x, 1.4 * Math.sin(2.0 * Math.PI * (x - 2.0) / 4.0));
                }).toList();
    }

    private static List<MetricPoint> separatedWavePackets(int count) {
        List<MetricPoint> result = new java.util.ArrayList<>();
        for (int packet = 0; packet < count; packet++) {
            double start = packet * 100.0;
            if (result.isEmpty() || result.get(result.size() - 1).xMeters() < start) {
                result.add(p(start, 0));
            }
            for (int step = 1; step <= 24; step++) {
                double x = start + step;
                result.add(p(x, 1.4 * Math.sin(2.0 * Math.PI * step / 12.0)));
            }
        }
        result.add(p(10_000, 0));
        return List.copyOf(result);
    }

    private static MetricPoint interpolateForTest(MetricPoint first, MetricPoint second,
            double fraction) {
        return p(first.xMeters() + fraction * (second.xMeters() - first.xMeters()),
                first.yMeters() + fraction * (second.yMeters() - first.yMeters()));
    }

    private static ImageCostField graphRidgeImage(List<MetricPoint> line) {
        return ridgeImage((x, y) -> {
            int segment = 0;
            while (segment + 1 < line.size() - 1
                    && line.get(segment + 1).xMeters() < x) {
                segment++;
            }
            MetricPoint first = line.get(segment);
            MetricPoint second = line.get(segment + 1);
            double fraction = (x - first.xMeters())
                    / (second.xMeters() - first.xMeters());
            double center = first.yMeters()
                    + fraction * (second.yMeters() - first.yMeters());
            return gaussian(y - center);
        }, (x, y) -> x >= line.get(0).xMeters()
                && x <= line.get(line.size() - 1).xMeters());
    }

    private static ImageCostField polylineRidgeImage(List<MetricPoint> line) {
        return ridgeImage((x, y) -> {
            double distance = Double.POSITIVE_INFINITY;
            for (int index = 1; index < line.size(); index++) {
                MetricPoint first = line.get(index - 1);
                MetricPoint second = line.get(index);
                distance = Math.min(distance, distanceToSegment(x, y, first.xMeters(),
                        first.yMeters(), second.xMeters(), second.yMeters()));
            }
            return gaussian(distance);
        }, (x, y) -> true);
    }

    private static ImageCostField ridgeImage(ToDoubleBiFunction<Double, Double> intensity,
            BiPredicate<Double, Double> observed) {
        double pitch = 0.25;
        int width = 321;
        int height = 81;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = x * pitch;
                double metricY = -10.0 + y * pitch;
                int index = y * width + x;
                valid[index] = observed.test(metricX, metricY);
                values[index] = valid[index] ? intensity.applyAsDouble(metricX, metricY) : Double.NaN;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "analytic-ridge",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("analytic-ridge",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                p(0, -10), pitch, 0, 0, pitch, 1),
                MetricRegion.rectangle(0, -10, 80, 10), 1.0);
    }

    private static ImageCostField wideStraightRidgeImage(int maximumX) {
        int height = 21;
        int width = maximumX + 1;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            double metricY = -10.0 + y;
            double value = gaussian(metricY);
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                values[index] = value;
                valid[index] = true;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                        "wide-analytic-ridge", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("wide-analytic-ridge",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                p(0, -10), 1.0, 0, 0, 1.0, 1),
                MetricRegion.rectangle(0, -10, maximumX, 10), 1.0);
    }

    private static double gaussian(double distance) {
        return 0.05 + 0.9 * Math.exp(-distance * distance / 0.72);
    }

    private static List<MetricPoint> points(SyntheticHeatmapScene scene) {
        return QualityTestFixtures.metric(scene.defectiveCandidate().orElseThrow().points());
    }

    private static MetricPoint p(double x, double y) {
        return new MetricPoint(x, y);
    }

    private static ImageCostField narrowUnavailableGapImage() {
        int width = 2001;
        int height = 1001;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                double distance = Math.min(distanceToSegment(x * 0.1, -50 + y * 0.1, 0, 0, 99, 10),
                        Math.min(distanceToSegment(x * 0.1, -50 + y * 0.1, 99, 10, 101, 0),
                                distanceToSegment(x * 0.1, -50 + y * 0.1, 101, 0, 200, 0)));
                values[index] = 0.1 + 0.9 * Math.exp(-distance * distance / 0.5);
            }
            int masked = y * width + 1000;
            values[masked] = Double.NaN;
            valid[masked] = false;
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "narrow-gap",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("narrow-gap",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, -50), 0.1, 0, 0, 0.1, 1),
                MetricRegion.rectangle(0, -50, 200, 50), 0.1);
    }

    private static double distanceToSegment(double x, double y, double ax, double ay,
            double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double fraction = Math.max(0.0, Math.min(1.0,
                ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(x - (ax + fraction * dx), y - (ay + fraction * dy));
    }
}
