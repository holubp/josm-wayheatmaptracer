package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

/** Detached scalar-capture regressions for modern engines. */
class V022RasterEvidenceCaptureTest {
    @Test
    void scalarMappingPrecedesFilteringAndCaptureFunctionIsNotRetained() {
        BufferedImage image = image(40, 30);
        GeographicPoint origin = geographic(10, 15);
        GeographicPoint end = geographic(28, 15);
        EvidenceFieldLineage lineage = lineage();
        EvidenceSnapshot snapshot = new RasterEvidenceCapture().capture("capture", image,
                List.of(origin, end), V022RasterEvidenceCaptureTest::geographic,
                EvidenceResolution.nativeSource(1.0, 1.0), 3.0, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                List.of(new RasterEvidenceCapture.FieldSpec("hot", pixel ->
                        (pixel >>> 16 & 0xff) / 255.0, lineage,
                        field -> field.convolveSeparable(new double[] {1, 2, 1}))));

        assertEquals(List.of("separable-[1.0, 2.0, 1.0]"),
                snapshot.fields().get("hot").lineage().scalarOperations());
        assertTrue(snapshot.routePositionAuthorized(snapshot.coordinateFrame().toMetric(origin)));
        assertEquals(1, snapshot.independentEvidenceGroups().size());
    }

    @Test
    void incompleteRasterCannotPretendToOwnTheDecisionCorridor() {
        BufferedImage image = image(8, 8);

        assertThrows(IllegalArgumentException.class, () -> new RasterEvidenceCapture().capture(
                "capture", image, List.of(geographic(1, 1), geographic(7, 7)),
                V022RasterEvidenceCaptureTest::geographic,
                EvidenceResolution.renderedOnly(1.0), 5.0, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                List.of(RasterEvidenceCapture.FieldSpec.direct("hot", pixel -> 1.0, lineage()))));
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xffff0000);
            }
        }
        return image;
    }

    private static GeographicPoint geographic(RasterPoint point) {
        return geographic(point.x(), point.y());
    }

    private static GeographicPoint geographic(double x, double y) {
        return new GeographicPoint(42.0 + y / 111_000.0, 19.0 + x / 82_000.0);
    }

    private static EvidenceFieldLineage lineage() {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
    }
}
