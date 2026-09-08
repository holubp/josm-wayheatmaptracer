package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
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
    void T068_cleanedLabelCannotBypassTheSameDefect() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S04", 29);
        FinalGeometryEvaluator.Result raw = evaluate(scene, points(scene), false);
        FinalGeometryEvaluator.Result cleaned = evaluate(scene, points(scene), true);

        assertEquals(raw.disposition(), cleaned.disposition());
        assertEquals(raw.findings(), cleaned.findings());
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, cleaned.disposition());
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
}
