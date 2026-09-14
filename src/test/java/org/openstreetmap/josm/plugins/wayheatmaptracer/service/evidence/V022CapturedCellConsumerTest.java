package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Consumer regression: four valid output vertices do not certify source support between them. */
class V022CapturedCellConsumerTest {
    @Test
    void profileSamplerRejectsUnsupportedCellButKeepsAdjacentSupport() {
        EvidenceSnapshot snapshot = snapshot();
        ScalarEvidenceField field = snapshot.fields().get("scalar");
        ProbabilisticProfileFactory factory = new ProbabilisticProfileFactory();
        assertTrue(factory.sample(snapshot, field, new MetricPoint(50.25, 50.25)).isEmpty());
        assertTrue(factory.sample(snapshot, field, new MetricPoint(52.25, 50.25)).isPresent());
    }

    @Test
    void orientationRejectsUnsupportedCenterButMeasuresAdjacentRidge() {
        EvidenceSnapshot snapshot = snapshot();
        ScalarEvidenceField field = snapshot.fields().get("scalar");
        ImageOrientationDescriptor descriptor = new ImageOrientationDescriptor();
        assertEquals(ImageOrientationSupport.Status.INVALID_CENTER,
            descriptor.describe(snapshot, field, new MetricPoint(50.25, 50.25), 1).support().status());
        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
            descriptor.describe(snapshot, field, new MetricPoint(70.25, 50.25), 1).support().status());
    }

    @Test
    void routeLocalizationRejectsUnsupportedCellButKeepsAdjacentRidge() {
        EvidenceSnapshot snapshot = snapshot();
        ImageCostField image = ImageCostField.fromEvidence(snapshot, "scalar");
        MetricPoint tangent = new MetricPoint(1, 0);
        assertTrue(image.sampleRoute(new MetricPoint(50.25, 50.25), tangent).isEmpty());
        assertTrue(image.sampleRoute(new MetricPoint(70.25, 50.25), tangent)
            .orElseThrow().directlyLocalized());
    }

    private static EvidenceSnapshot snapshot() {
        int size = 101;
        double[] values = new double[size * size];
        boolean[] vertices = new boolean[values.length];
        boolean[] cells = new boolean[(size - 1) * (size - 1)];
        Arrays.fill(vertices, true);
        Arrays.fill(cells, true);
        cells[50 * (size - 1) + 50] = false;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double normal = y - 50.25;
                values[y * size + x] = 0.02 + 0.98 * Math.exp(-0.5 * normal * normal);
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "analytic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, vertices, cells, lineage);
        RasterMetricTransform transform = new RasterMetricTransform("identity-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            1, 0, 0, 1, 1);
        MetricRegion region = new MetricRegion(List.of(List.of(new MetricPoint(-0.5, -0.5),
            new MetricPoint(100.5, -0.5), new MetricPoint(100.5, 100.5), new MetricPoint(-0.5, 100.5))));
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        return new EvidenceSnapshot("cell-consumers", frame, transform,
            EvidenceResolution.nativeSource(1, 1), region, region, Map.of("scalar", field), "synthetic");
    }
}
