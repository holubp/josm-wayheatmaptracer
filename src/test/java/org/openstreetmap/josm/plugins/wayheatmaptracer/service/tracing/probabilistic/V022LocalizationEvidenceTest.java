package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

class V022LocalizationEvidenceTest {
    private static final int WIDTH = 121;
    private static final int HEIGHT = 61;

    @Test
    void realFactoryMeasuresHorizontalImageDirectionWhenSourceWaySlopes() {
        EvidenceSnapshot evidence = snapshot((x, y) -> gaussian(y, 30.0, 1.8));
        List<MetricPoint> source = List.of(new MetricPoint(10, 25), new MetricPoint(110, 34));

        ProbabilisticProfile profile = new ProbabilisticProfileFactory()
            .create(source, 6.0, 8.0, false, evidence, evidence.fields().get("scalar"))
            .stream().filter(candidate -> !candidate.modes().isEmpty())
            .min(Comparator.comparingDouble(candidate -> Math.abs(candidate.anchor().xMeters() - 60.0)))
            .orElseThrow();

        assertTrue(profile.orientationCertainty() > 0.0);
        assertEquals(0.0, undirectedDistance(0.0, profile.supportedDirectionsRadians().get(0)), 0.03);
    }

    @Test
    void realFactoryRetainsWeakerLocallyProminentParallelMode() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
            0.6 * gaussian(y, 26.0, 1.2) + gaussian(y, 35.0, 1.2)));

        ProbabilisticProfile profile = centerProfile(evidence);
        List<Double> centers = profile.modes().stream()
            .map(ProbabilisticProfile.Mode::coreCenterMeters).sorted().toList();

        assertEquals(2, centers.size());
        assertEquals(-4.0, centers.get(0), 0.55);
        assertEquals(5.0, centers.get(1), 0.55);
        assertTrue(profile.samples().stream().anyMatch(sample -> sample.valid()
            && Math.abs(sample.offsetMeters() + 4.0) < 1e-9 && sample.intensity() > 0.59));
    }

    @Test
    void realFactoryRetainsEqualParallelModes() {
        EvidenceSnapshot evidence = snapshot((x, y) -> Math.min(1.0,
            0.9 * gaussian(y, 26.0, 1.2) + 0.9 * gaussian(y, 35.0, 1.2)));

        ProbabilisticProfile profile = centerProfile(evidence);

        assertEquals(2, profile.modes().size());
    }

    private static ProbabilisticProfile centerProfile(EvidenceSnapshot evidence) {
        return new ProbabilisticProfileFactory()
            .create(List.of(new MetricPoint(10, 30), new MetricPoint(110, 30)),
                6.0, 7.0, false, evidence, evidence.fields().get("scalar"))
            .stream().min(Comparator.comparingDouble(profile -> Math.abs(profile.anchor().xMeters() - 60.0)))
            .orElseThrow();
    }

    private static EvidenceSnapshot snapshot(Intensity intensity) {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = Math.max(0.02, Math.min(1.0, intensity.value(x, y)));
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid, lineage);
        GeographicPoint origin = new GeographicPoint(0, 0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        RasterMetricTransform transform = new RasterMetricTransform("localization-test-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            1, 0, 0, 1, 1);
        return new EvidenceSnapshot("localization", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(1, 1, 119, 59),
            MetricRegion.rectangle(0, 0, 120, 60), Map.of("scalar", field), "synthetic-localization");
    }

    private static double gaussian(double value, double center, double sigma) {
        double normalized = (value - center) / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(first - second) % Math.PI;
        return Math.min(difference, Math.PI - difference);
    }

    @FunctionalInterface
    private interface Intensity {
        double value(double x, double y);
    }
}
