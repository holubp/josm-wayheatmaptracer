package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.BilinearSegmentIntegral;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ScalarFieldGridViewTest {
    @Test
    void intensityViewPreservesCropReductionAndInverseCoordinateMapping() {
        ScalarIntensityField field = intensity(7, 11, 3, 2, 4,
            new float[] {0, 0.25f, 0.5f, 0, 0.25f, 0.5f},
            allValid(6), allValid(2));
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(field);

        assertEquals(3, grid.width());
        assertEquals(2, grid.height());
        assertEquals(7, grid.originX());
        assertEquals(11, grid.originY());
        assertEquals(4, grid.reduction());
        RasterPoint base = new RasterPoint(12.0, 13.0);
        RasterPoint gridPoint = grid.toGrid(base);
        assertEquals(new RasterPoint(1.25, 0.5), gridPoint);
        assertEquals(base, grid.toBaseRaster(gridPoint));
    }

    @Test
    void intensityViewReadsExactStoredNodesWithoutClampingOrRemapping() {
        float[] source = {0.12345679f, 1.25f, 0.75f, 0.5f};
        ScalarIntensityField field = intensity(0, 0, 2, 2, 1, source,
            allValid(4), allValid(1));
        source[0] = 0.9f;
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(field);

        assertEquals((double) 0.12345679f, grid.nodeValue(0, 0), 0.0);
        assertEquals(1.25, grid.nodeValue(1, 0), 0.0);
    }

    @Test
    void preservesCellMaskEvenWhenEveryCornerNodeIsValid() {
        ScalarIntensityField field = intensity(0, 0, 2, 2, 1,
            new float[] {0.1f, 0.2f, 0.3f, 0.4f}, allValid(4), new boolean[] {false});
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(field);

        assertTrue(Double.isFinite(grid.nodeValue(0, 0)));
        assertTrue(Double.isFinite(grid.nodeValue(1, 0)));
        assertTrue(Double.isFinite(grid.nodeValue(0, 1)));
        assertTrue(Double.isFinite(grid.nodeValue(1, 1)));
        assertFalse(grid.supportsCell(0, 0));
    }

    @Test
    void invalidIntensityNodeIsUnavailableAsNaN() {
        ScalarIntensityField field = intensity(0, 0, 2, 2, 1,
            new float[] {0.1f, 0.2f, 0.3f, 0.4f},
            new boolean[] {true, false, true, true}, new boolean[] {false});
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(field);

        assertTrue(Double.isNaN(grid.nodeValue(1, 0)));
        assertEquals(0.1, grid.nodeValue(0, 0), 1e-8);
    }

    @Test
    void outsideNodesThrowAndOutsideCellsAreUnsupported() {
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(intensity(0, 0, 2, 2, 1,
            new float[] {0, 0, 0, 0}, allValid(4), allValid(1)));

        assertFalse(grid.supportsCell(-1, 0));
        assertFalse(grid.supportsCell(1, 0));
        assertFalse(grid.supportsCell(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.nodeValue(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.nodeValue(2, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.nodeValue(0, 2));
    }

    @Test
    void segmentIntegralUsesNonzeroOriginLinearIntensityAsAnExactGridField() {
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(intensity(5, 7, 3, 2, 3,
            new float[] {0, 0.5f, 1, 0, 0.5f, 1}, allValid(6), allValid(2)));
        RasterPoint from = grid.toGrid(new RasterPoint(5.6, 7.6));
        RasterPoint to = grid.toGrid(new RasterPoint(10.4, 9.4));

        var result = BilinearSegmentIntegral.evaluate(grid, from, to, CancellationProbe.NONE, () -> { });

        assertEquals(BilinearSegmentIntegral.Status.COMPLETE, result.status());
        assertEquals(0.5, result.mean().orElseThrow(), 1e-14);
        assertEquals(0.1, result.minimum().orElseThrow(), 1e-14);
    }

    @Test
    void evidenceViewPreservesValuesMaskAndIdentityMapping() {
        double[] values = {0.125, 0.25, 0.5, 0.125, 0.25, 0.5};
        ScalarEvidenceField field = new ScalarEvidenceField(3, 2, values, allValid(6),
            new boolean[] {true, false}, syntheticLineage());
        ScalarFieldGridView grid = ScalarFieldGridView.fromEvidence(field);

        assertEquals(3, grid.width());
        assertEquals(2, grid.height());
        assertEquals(0, grid.originX());
        assertEquals(0, grid.originY());
        assertEquals(1, grid.reduction());
        assertEquals(0.25, grid.nodeValue(1, 0), 0.0);
        assertTrue(grid.supportsCell(0, 0));
        assertFalse(grid.supportsCell(1, 0));
        RasterPoint point = new RasterPoint(1.25, 0.5);
        assertEquals(point, grid.toGrid(point));
        assertEquals(point, grid.toBaseRaster(point));
    }

    @Test
    void nearestValidLegacySampleCannotMakeAnInvalidCellSupported() {
        ScalarIntensityField field = intensity(0, 0, 2, 2, 1,
            new float[] {0.25f, 0.75f, 0.5f, 0.9f},
            new boolean[] {true, false, true, true}, new boolean[] {false});
        ScalarFieldGridView grid = ScalarFieldGridView.fromIntensity(field);

        assertEquals(0.25, field.sample(0.1, 0.1), 0.0,
            "legacy sampling retains its nearest-valid fallback");
        assertFalse(grid.supportsCell(0, 0));
        assertTrue(Double.isNaN(grid.nodeValue(1, 0)));
        var integral = BilinearSegmentIntegral.evaluate(grid, new RasterPoint(0.1, 0.1),
            new RasterPoint(0.8, 0.8), CancellationProbe.NONE, () -> { });
        assertEquals(BilinearSegmentIntegral.Status.UNAVAILABLE, integral.status());
        assertEquals(BilinearSegmentIntegral.Reason.UNSUPPORTED_CELL, integral.reason());
    }

    private static ScalarIntensityField intensity(int originX, int originY, int width, int height,
            int reduction, float[] values, boolean[] valid, boolean[] interpolationValid) {
        return new ScalarIntensityField(originX, originY, width, height, reduction,
            values, valid, interpolationValid);
    }

    private static boolean[] allValid(int size) {
        boolean[] values = new boolean[size];
        java.util.Arrays.fill(values, true);
        return values;
    }

    private static EvidenceFieldLineage syntheticLineage() {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "analytic-grid",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
    }
}
