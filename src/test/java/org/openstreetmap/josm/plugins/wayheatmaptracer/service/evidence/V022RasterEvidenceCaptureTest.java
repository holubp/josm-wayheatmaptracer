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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Detached scalar-capture regressions for modern engines. */
class V022RasterEvidenceCaptureTest {
    @Test
    void selectedRasterCanFitWhileOptionalScalarCopyGetsTypedResourceRefusal() {
        long inputPixels = 16_777_216L;
        long outputPixels = 3_000_000L;
        var fields = List.of(RasterEvidenceCapture.FieldSpec.direct("selected",
                pixel -> 1.0, lineage()));
        assertTrue(RasterEvidenceCapture.estimatedPeakWorkingBytes(
                inputPixels, outputPixels, fields) <= RasterEvidenceCapture.MAX_WORKING_BYTES);
        assertThrows(RasterEvidenceCapture.ResourceLimitException.class,
                () -> RasterEvidenceCapture.requireScalarCaptureBudget(
                        inputPixels, outputPixels, fields));
    }

    @Test
    void scalarMappingPrecedesMetricResamplingAndFiltering() {
        LocalMetricFrame frame = frame();
        BufferedImage image = image(40, 30);
        MetricRasterGrid grid = new MetricRasterGrid(frame, new MetricPoint(0.25, 0.25),
                1, 0, 0, 1, 1, 38, 30);
        EvidenceFieldLineage lineage = lineage();
        EvidenceSnapshot snapshot = new RasterEvidenceCapture().capture("capture", image, valid(image),
                List.of(metric(frame, 10, 15), metric(frame, 28, 15)), inverse(frame), grid,
                EvidenceResolution.nativeSource(1.0, 1.0), 3.0, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                List.of(RasterEvidenceCapture.FieldSpec.separable("hot", pixel ->
                        (pixel >>> 16 & 0xff) / 255.0, lineage, new double[] {1, 2, 1})),
                CancellationProbe.NONE);

        assertEquals(List.of("strict-bilinear-metric-resample-v2",
                        "separable-[1.0, 2.0, 1.0]"),
                snapshot.fields().get("hot").lineage().scalarOperations());
        assertTrue(snapshot.routePositionAuthorized(snapshot.coordinateFrame().toMetric(
                metric(frame, 10, 15))));
        assertEquals(1, snapshot.independentEvidenceGroups().size());
    }

    @Test
    void incompleteMetricGridCannotPretendToOwnTheDecisionCorridor() {
        LocalMetricFrame frame = frame();
        BufferedImage image = image(8, 8);
        MetricRasterGrid grid = new MetricRasterGrid(frame, new MetricPoint(0.25, 0.25),
                1, 0, 0, 1, 1, 6, 6);

        assertThrows(IllegalArgumentException.class, () -> new RasterEvidenceCapture().capture(
                "capture", image, valid(image),
                List.of(metric(frame, 1, 1), metric(frame, 5, 5)), inverse(frame), grid,
                EvidenceResolution.renderedOnly(1.0), 5.0, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                List.of(RasterEvidenceCapture.FieldSpec.direct("hot", pixel -> 1.0, lineage())),
                CancellationProbe.NONE));
    }

    @Test
    void precomputedAggregateScalarKeepsDoublePrecisionThroughMetricResampling() {
        LocalMetricFrame frame = frame();
        BufferedImage image = image(40, 30);
        double[] scalar = new double[40 * 30];
        java.util.Arrays.fill(scalar, 0.73123456789);
        MetricRasterGrid grid = new MetricRasterGrid(frame, new MetricPoint(0.25, 0.25),
                1, 0, 0, 1, 1, 38, 30);
        EvidenceFieldLineage aggregate = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.ALL_COLOR_AGGREGATE,
                "all-colors-combined", EvidenceCorrelationGroup.STRAVA_RENDERINGS, true);

        EvidenceSnapshot snapshot = new RasterEvidenceCapture().captureWithMetricScalarSource(
                "aggregate-capture", image, valid(image), scalar,
                List.of(metric(frame, 10, 15), metric(frame, 28, 15)),
                List.of(new MetricPoint(10, 15), new MetricPoint(28, 15)), inverse(frame), grid,
                EvidenceResolution.nativeSource(1.0, 1.0), 3.0, "aggregate-source",
                aggregate.acquisitionKind(), "aggregate", aggregate, CancellationProbe.NONE);

        assertEquals(0.73123456789,
                snapshot.fields().get("aggregate").sample(10, 15).orElseThrow(), 1.0e-12);
        assertEquals(aggregate.derivationKind(),
                snapshot.fields().get("aggregate").lineage().derivationKind());
        assertThrows(IllegalArgumentException.class, () ->
                new RasterEvidenceCapture().captureWithMetricScalarSource("bad-size", image,
                        valid(image), new double[1],
                        List.of(metric(frame, 10, 15), metric(frame, 28, 15)),
                        List.of(new MetricPoint(10, 15), new MetricPoint(28, 15)), inverse(frame), grid,
                        EvidenceResolution.nativeSource(1.0, 1.0), 3.0, "aggregate-source",
                        aggregate.acquisitionKind(), "aggregate", aggregate, CancellationProbe.NONE));
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

    private static boolean[] valid(BufferedImage image) {
        boolean[] validity = new boolean[Math.multiplyExact(image.getWidth(), image.getHeight())];
        java.util.Arrays.fill(validity, true);
        return validity;
    }

    private static LocalMetricFrame frame() {
        GeographicPoint origin = new GeographicPoint(42, 19);
        return LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
    }

    private static GeographicPoint metric(LocalMetricFrame frame, double x, double y) {
        return frame.toGeographic(new MetricPoint(x, y));
    }

    private static SupportedInputRasterTransform inverse(LocalMetricFrame frame) {
        return SupportedInputRasterTransform.localMetricAffine(
                frame, new RasterPoint(0, 0), 1, 0, 0, 1);
    }

    private static EvidenceFieldLineage lineage() {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
    }
}
