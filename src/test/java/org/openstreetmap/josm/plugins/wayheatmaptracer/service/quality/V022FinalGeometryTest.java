package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
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

    private FinalGeometryEvaluator.Result evaluate(SyntheticHeatmapScene scene, List<MetricPoint> geometry,
            boolean cleaned) {
        return evaluator.evaluate(new FinalGeometryEvaluator.Request("candidate", geometry,
                QualityTestFixtures.image(scene), scene.rasterPitchMeters(), Set.of(0, geometry.size() - 1),
                List.of(), cleaned, false, false, false));
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
