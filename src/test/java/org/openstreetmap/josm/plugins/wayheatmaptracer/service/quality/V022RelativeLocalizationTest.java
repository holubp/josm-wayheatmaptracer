package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;

/** G6-07: final support measures localization, independently of absolute brightness. */
class V022RelativeLocalizationTest {
    @Test
    void uniformBrightImageCannotLocalizeAStraightRoute() {
        FinalGeometryEvaluator.Result result = evaluate(QualityTestFixtures.constantImage(0.9));

        assertEquals(0.0, result.directlySupportedLengthMeters(), 1e-9);
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void faintCoherentRidgeRetainsDirectLocalization() {
        FinalGeometryEvaluator.Result result = evaluate(ridge(0.15));

        assertTrue(result.directlySupportedLengthMeters() >= 0.95 * result.totalLengthMeters());
        assertEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void brightCoherentRidgeIsASupportedControl() {
        FinalGeometryEvaluator.Result result = evaluate(ridge(0.9));

        assertTrue(result.directlySupportedLengthMeters() >= 0.95 * result.totalLengthMeters());
        assertEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void zeroLocalizationAgreementCannotBecomeDirectFinalSupport() {
        double[] profile = {0.21726069195684994, 0.5845368280201871, 0.018938175767871712,
                0.8561447967800481, 0.6584675007317778, 0.037449064138866284,
                0.6412364079896637, 0.7521073081974161, 0.7345609903567496,
                0.7858838746484419, 0.3146704255646897, 0.2986946186756281,
                0.7611067649919997, 0.8672588638967729, 0.6378794769192359,
                0.1970952982886227, 0.3948729867900528};
        ImageCostField image = profile(profile);
        ImageCostField.RouteSample sample = image.sampleRoute(new MetricPoint(0, 0),
                new MetricPoint(1, 0)).orElseThrow();

        assertEquals(0.0, sample.localizationConfidence(), 1e-12);
        assertFalse(sample.directlyLocalized());
        FinalGeometryEvaluator.Result result = evaluate(image);
        assertEquals(0.0, result.directlySupportedLengthMeters(), 1e-9);
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void duplicateFinalVerticesReturnTypedFindingsWithoutLosingOccurrences() {
        ImageCostField image = ridge(0.8);
        FinalGeometryEvaluator.Result result = assertDoesNotThrow(() -> new FinalGeometryEvaluator().evaluate(
                new FinalGeometryEvaluator.Request("duplicate",
                        List.of(new MetricPoint(-20, 0), new MetricPoint(0, 0),
                                new MetricPoint(0, 0), new MetricPoint(20, 0)),
                        image, 1.0, Set.of(), List.of(), false, false, false, false)));

        assertTrue(result.has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
        assertNotEquals(FinalGeometryEvaluator.Disposition.APPLICABLE, result.disposition());
    }

    @Test
    void unavailableComparisonChordCannotAuthorizeUnsupportedApex() {
        List<MetricPoint> points = List.of(new MetricPoint(-40, 0),
                new MetricPoint(-2, 0), new MetricPoint(0, 1.1),
                new MetricPoint(2, 0), new MetricPoint(40, 0));
        FinalGeometryEvaluator.Result complete = evaluateApex(points, fineRidge(false));
        FinalGeometryEvaluator.Result missingChord = evaluateApex(points, fineRidge(true));

        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, complete.disposition());
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, missingChord.disposition());
        assertTrue(missingChord.has(
                FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION));
    }

    private static FinalGeometryEvaluator.Result evaluate(ImageCostField image) {
        return new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request("straight",
                List.of(new MetricPoint(-20, 0), new MetricPoint(0, 0), new MetricPoint(20, 0)),
                image, 1.0, Set.of(0, 2), List.of(), false, false, false, false));
    }

    private static FinalGeometryEvaluator.Result evaluateApex(
            List<MetricPoint> points, ImageCostField image) {
        return new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(
                "apex", points, image, 1.0, Set.of(0, points.size() - 1),
                List.of(), false, false, false, false));
    }

    private static ImageCostField fineRidge(boolean missingChordCell) {
        int width = 1_001;
        int height = 201;
        double pitch = 0.1;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        boolean[] cells = new boolean[(width - 1) * (height - 1)];
        java.util.Arrays.fill(valid, true);
        java.util.Arrays.fill(cells, true);
        for (int y = 0; y < height; y++) {
            double lateral = -10.0 + y * pitch;
            for (int x = 0; x < width; x++) {
                values[y * width + x] = 0.1
                        + 0.8 * Math.exp(-lateral * lateral / 8.0);
            }
        }
        if (missingChordCell) {
            cells[99 * (width - 1) + 502] = false;
            cells[100 * (width - 1) + 502] = false;
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid, cells,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "fine-ridge",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, RasterMetricTransform.metricGrid(
                new MetricPoint(-50, -10), pitch, 0, 0, pitch),
                MetricRegion.rectangle(-50, -10, 50, 10), 1.0);
    }

    private static ImageCostField ridge(double amplitude) {
        int size = 101;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < size; y++) {
            double lateral = y - 50.0;
            for (int x = 0; x < size; x++) {
                values[y * size + x] = amplitude * Math.exp(-0.5 * lateral * lateral / 4.0);
                valid[y * size + x] = true;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "analytic-gaussian",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("analytic-unit-grid",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-50, -50), 1, 0, 0, 1, 1),
                MetricRegion.rectangle(-50, -50, 50, 50), 1.0);
    }

    private static ImageCostField profile(double[] profile) {
        int size = 101;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < size; y++) {
            int profileIndex = y - 50 + 8;
            for (int x = 0; x < size; x++) {
                int index = y * size + x;
                values[index] = profileIndex >= 0 && profileIndex < profile.length
                        ? profile[profileIndex] : 0.0;
                valid[index] = true;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "disagreed-profile",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field, new RasterMetricTransform("disagreed-unit-grid",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-50, -50), 1, 0, 0, 1, 1),
                MetricRegion.rectangle(-50, -50, 50, 50), 1.0);
    }
}
