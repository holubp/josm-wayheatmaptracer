package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Exact metric-raster fallback regressions for G6-04 and G6-11. */
class V022MetricRasterCaptureFallbackTest {
    @Test
    void completeOutputCellSupportBlocksFractionalSamplingAcrossAnInputHole() {
        LocalMetricFrame frame = frame();
        BufferedImage image = solidImage(16, 16, 0xffffffff);
        boolean[] validity = allValid(16, 16);
        for (int y = 0; y < image.getHeight(); y++) {
            validity[y * image.getWidth() + 5] = false;
        }
        MetricRasterGrid grid = grid(frame, new MetricPoint(2.25, 2.25),
                1, 0, 0, 1, 4, 3, 3);
        SupportedInputRasterTransform transform = SupportedInputRasterTransform.localMetricAffine(
                frame, new RasterPoint(0, 0), 1, 0, 0, 1);

        EvidenceSnapshot snapshot = capture(image, validity,
                List.of(metric(frame, 2.25, 6.25), metric(frame, 10.25, 6.25)),
                transform, grid, EvidenceResolution.renderedOnly(1), 0.4,
                direct(), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("hot");
        ImageCostField costs = new ImageCostField(field, snapshot.transform(),
                snapshot.decisionRegion(), snapshot.resolution().effectivePitchMeters());
        ProbabilisticProfileFactory profiles = new ProbabilisticProfileFactory();

        assertTrue(costs.sample(new MetricPoint(5.25, 6.25)).isEmpty());
        assertTrue(profiles.sample(snapshot, field, new MetricPoint(5.25, 6.25)).isEmpty());
        assertEquals(1.0, costs.sample(new MetricPoint(9.25, 6.25)).orElseThrow().intensity(), 0.0);
        assertEquals(1.0, profiles.sample(snapshot, field,
                new MetricPoint(9.25, 6.25)).orElseThrow(), 0.0);
    }

    @Test
    void nonlinearWebMercatorMappingIsUsedExactlyAtFractionalOutputCenters() {
        LocalMetricFrame frame = frame();
        int zoom = 20;
        double worldSize = Math.scalb(256.0, zoom);
        GeographicPoint origin = frame.origin();
        double originWorldX = (origin.longitudeDegrees() + 180.0) / 360.0 * worldSize;
        double latitudeRadians = Math.toRadians(origin.latitudeDegrees());
        double originWorldY = (1.0 - Math.log(Math.tan(latitudeRadians)
                + 1.0 / Math.cos(latitudeRadians)) / Math.PI) * 0.5 * worldSize;
        SupportedInputRasterTransform transform = SupportedInputRasterTransform.webMercator(
                zoom, originWorldX - 10.5, originWorldY - 10.5, 1.0);
        SupportedInputRasterTransform oversampled = SupportedInputRasterTransform.webMercator(
                zoom, originWorldX - 5.25, originWorldY - 5.25, 2.0);
        assertEquals(10.0, oversampled.toRasterCenter(origin).x(), 1e-9);
        assertEquals(10.0, oversampled.toRasterCenter(origin).y(), 1e-9);
        MetricRasterGrid grid = grid(frame, new MetricPoint(0.25, 0.25),
                1, 0, 0, 1, 0.2, 8, 8);
        RasterEvidenceCapture.FieldSpec managed = RasterEvidenceCapture.FieldSpec.direct(
                "hot", pixel -> (pixel >>> 16 & 0xff) / 255.0, managedLineage());

        EvidenceSnapshot snapshot = new RasterEvidenceCapture().capture("capture",
                webGradientImage(64, 64), allValid(64, 64),
                List.of(metric(frame, 0.4, 0.8), metric(frame, 1.2, 0.8)),
                transform, grid, EvidenceResolution.renderedOnly(0.4), 0.1, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                List.of(managed), CancellationProbe.NONE);

        GeographicPoint sampleGeographic = metric(frame, 0.45, 0.65);
        double sourceX = (sampleGeographic.longitudeDegrees() + 180.0)
                / 360.0 * worldSize - (originWorldX - 10.5) - 0.5;
        double sampleLatitude = Math.toRadians(sampleGeographic.latitudeDegrees());
        double sourceY = (1.0 - Math.log(Math.tan(sampleLatitude)
                + 1.0 / Math.cos(sampleLatitude)) / Math.PI) * 0.5 * worldSize
                - (originWorldY - 10.5) - 0.5;
        double independentlyBilinear = (3.0 * sourceX + 2.0 * sourceY) / 255.0;
        double sampled = snapshot.fields().get("hot").sample(1, 2).orElseThrow();

        assertEquals(independentlyBilinear, sampled, 1e-12);
        assertEquals("exact-analytic-cell-support-strict-bilinear-v2",
                snapshot.resampling().method());
        assertEquals("web-mercator-world-pixel-boundary-v1",
                snapshot.resampling().sourceTransformKind());
    }

    @Test
    void rotatedMetricOutputGridIsTheExactAuthoritativeTransform() {
        LocalMetricFrame frame = frame();
        double diagonal = Math.sqrt(0.5);
        MetricRasterGrid grid = grid(frame, new MetricPoint(5, 5),
                diagonal, diagonal, -diagonal, diagonal, 0.5, 8, 8);
        SupportedInputRasterTransform inverse =
                SupportedInputRasterTransform.localMetricAffine(
                        frame, new RasterPoint(8, 8), 1, 0, 0, 1);
        EvidenceSnapshot snapshot = capture(gradientImage(24, 24), allValid(24, 24),
                List.of(metric(frame, 4.5, 5.5), metric(frame, 5.5, 6.5)), inverse, grid,
                EvidenceResolution.nativeSource(2.4, 0.4), 0.2, direct(), CancellationProbe.NONE);

        MetricPoint expected = new MetricPoint(5 + 1.5 * diagonal - 0.5 * diagonal,
                5 + 1.5 * diagonal + 0.5 * diagonal);
        assertEquals(expected, snapshot.transform().pixelCenterToMetric(3, 1));
        RasterPoint roundTrip = snapshot.transform().metricToPixelCenter(expected);
        assertEquals(3.0, roundTrip.x(), 1e-12);
        assertEquals(1.0, roundTrip.y(), 1e-12);
        assertEquals("METRIC_FIRST_PIXEL_CENTER", snapshot.transform().originKind().name());
        assertEquals("RASTER_PIXEL", snapshot.transform().axisUnit().name());
        assertEquals("exact-constructed-metric-grid-v1",
                snapshot.transform().accuracyCertificate().method());
    }

    @Test
    void rotatedGridUsesNonlinearWebMercatorBoundsAcrossTheCompleteCell() {
        LocalMetricFrame frame = frame();
        int zoom = 20;
        SupportedInputRasterTransform transform = webMercatorCentered(frame, zoom, 32, 45);
        double diagonal = Math.sqrt(0.5);
        MetricRasterGrid grid = grid(frame, new MetricPoint(0, 0),
                diagonal, diagonal, -diagonal, diagonal, 2, 4, 4);
        BufferedImage image = solidImage(64, 64, 0xffffffff);
        boolean[] validity = allValid(64, 64);
        for (int y = 0; y < image.getHeight(); y++) {
            validity[y * image.getWidth() + 25] = false;
        }
        MetricPoint sourceStart = grid.pixelCenterToMetric(0.5, 0.5);
        MetricPoint sourceEnd = grid.pixelCenterToMetric(2.5, 0.5);
        RasterEvidenceCapture.FieldSpec managed = RasterEvidenceCapture.FieldSpec.direct(
                "hot", pixel -> 1.0, managedLineage());

        EvidenceSnapshot snapshot = new RasterEvidenceCapture().capture("rotated-web",
                image, validity,
                List.of(frame.toGeographic(sourceStart), frame.toGeographic(sourceEnd)),
                transform, grid, EvidenceResolution.renderedOnly(0.4), 0.5, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                List.of(managed), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("hot");
        ImageCostField costs = new ImageCostField(field, snapshot.transform(),
                snapshot.decisionRegion(), snapshot.resolution().effectivePitchMeters());

        assertTrue(!field.supportsInterpolationCell(0, 0));
        assertTrue(field.supportsInterpolationCell(1, 0));
        assertTrue(costs.sample(grid.pixelCenterToMetric(0.5, 0.5)).isEmpty());
        assertEquals(1.0, costs.sample(grid.pixelCenterToMetric(1.5, 0.5))
                .orElseThrow().intensity(), 0.0);
    }

    @Test
    void analyticBoundsKeepInputBoundaryCellsMissingAndTheirNeighborUsable() {
        LocalMetricFrame frame = frame();
        MetricRasterGrid grid = grid(frame, new MetricPoint(-0.25, 2.25),
                1, 0, 0, 1, 4, 3, 3);
        MetricPoint sourceStart = grid.pixelCenterToMetric(0.5, 1);
        MetricPoint sourceEnd = grid.pixelCenterToMetric(1.5, 1);
        EvidenceSnapshot snapshot = capture(solidImage(16, 16, 0xffffffff), allValid(16, 16),
                List.of(frame.toGeographic(sourceStart), frame.toGeographic(sourceEnd)),
                linearInverse(frame), grid, EvidenceResolution.renderedOnly(1), 0.4,
                direct(), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("hot");
        ImageCostField costs = new ImageCostField(field, snapshot.transform(),
                snapshot.decisionRegion(), snapshot.resolution().effectivePitchMeters());

        assertTrue(!field.supportsInterpolationCell(0, 1));
        assertTrue(field.supportsInterpolationCell(1, 1));
        assertTrue(costs.sample(grid.pixelCenterToMetric(0.5, 1)).isEmpty());
        assertEquals(1.0, costs.sample(grid.pixelCenterToMetric(1.5, 1))
                .orElseThrow().intensity(), 0.0);
    }

    @Test
    void visibleWebMercatorMatchesSlideBoundsFormulaAndProtectsFractionalCells() {
        LocalMetricFrame frame = frame();
        GeographicPoint origin = frame.origin();
        double radius = 6_378_137.0;
        double originEast = radius * Math.toRadians(origin.longitudeDegrees());
        double originLatitude = Math.toRadians(origin.latitudeDegrees());
        double originNorth = radius
                * Math.log(Math.tan(Math.PI / 4.0 + originLatitude / 2.0));
        double minimumEast = originEast - 15.0;
        double maximumNorth = originNorth + 15.0;
        double projectionUnitsPerViewPixel = 0.5;
        double rasterPixelsPerViewPixel = 2.0;
        SupportedInputRasterTransform transform =
                SupportedInputRasterTransform.visibleWebMercator(
                        minimumEast, maximumNorth, projectionUnitsPerViewPixel,
                        rasterPixelsPerViewPixel);
        assertEquals(60.0, transform.toRasterCenter(origin).x(), 1e-9);
        assertEquals(60.0, transform.toRasterCenter(origin).y(), 1e-9);

        MetricRasterGrid grid = grid(frame, new MetricPoint(0, 0),
                1, 0, 0, 1, 2, 4, 4);
        GeographicPoint offCalibration = frame.toGeographic(
                grid.pixelCenterToMetric(0.37, 0.41));
        double expectedEast = radius * Math.toRadians(offCalibration.longitudeDegrees());
        double offLatitude = Math.toRadians(offCalibration.latitudeDegrees());
        double expectedNorth = radius
                * Math.log(Math.tan(Math.PI / 4.0 + offLatitude / 2.0));
        RasterPoint exact = transform.toRasterCenter(offCalibration);
        assertEquals((expectedEast - minimumEast) / projectionUnitsPerViewPixel
                * rasterPixelsPerViewPixel, exact.x(), 1e-9);
        assertEquals((maximumNorth - expectedNorth) / projectionUnitsPerViewPixel
                * rasterPixelsPerViewPixel, exact.y(), 1e-9);

        BufferedImage image = solidImage(128, 128, 0xffffffff);
        boolean[] validity = allValid(128, 128);
        for (int y = 0; y < image.getHeight(); y++) {
            validity[y * image.getWidth() + 65] = false;
        }
        MetricPoint sourceStart = grid.pixelCenterToMetric(0.5, 0.5);
        MetricPoint sourceEnd = grid.pixelCenterToMetric(2.5, 0.5);
        EvidenceFieldLineage visibleLineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
        EvidenceSnapshot snapshot = new RasterEvidenceCapture().capture(
                "visible-web", image, validity,
                List.of(frame.toGeographic(sourceStart), frame.toGeographic(sourceEnd)),
                transform, grid, EvidenceResolution.renderedOnly(0.4), 0.5, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                List.of(RasterEvidenceCapture.FieldSpec.direct(
                        "hot", pixel -> 1.0, visibleLineage)), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("hot");

        assertTrue(!field.supportsInterpolationCell(0, 0));
        assertTrue(field.supportsInterpolationCell(1, 0));
        assertTrue(field.sampleBilinear(0.5, 0.5).isEmpty());
        assertEquals(1.0, field.sampleBilinear(1.5, 0.5).orElseThrow(), 0.0);
        assertEquals("visible-web-mercator-projection-bounds-v1",
                snapshot.resampling().sourceTransformKind());
    }

    @Test
    void unsupportedAffineLiveSourceAndWebMercatorWrapOrPoleFailClearly() {
        LocalMetricFrame frame = frame();
        MetricRasterGrid grid = grid(frame, new MetricPoint(0.25, 0.25),
                1, 0, 0, 1, 1, 8, 8);
        EvidenceFieldLineage visibleLineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
        assertThrows(IllegalArgumentException.class, () -> new RasterEvidenceCapture().capture(
                "unsupported", solidImage(12, 12, 0xffffffff), allValid(12, 12),
                List.of(metric(frame, 2, 3), metric(frame, 5, 3)),
                linearInverse(frame), grid, EvidenceResolution.renderedOnly(1),
                0.2, "safe-source", EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                List.of(RasterEvidenceCapture.FieldSpec.direct(
                        "hot", pixel -> 1.0, visibleLineage)), CancellationProbe.NONE));

        SupportedInputRasterTransform web = SupportedInputRasterTransform.webMercator(15, 0, 0, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> web.toRasterCenter(new GeographicPoint(86, 0)));
        LocalMetricFrame wrapFrame = LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(0, 180), new GeographicPoint(-0.001, 179.99),
                new GeographicPoint(0.001, -179.99));
        assertThrows(IllegalArgumentException.class, () -> web.boundsForMetricCell(wrapFrame,
                List.of(new MetricPoint(-1_000, -1), new MetricPoint(1_000, -1),
                        new MetricPoint(1_000, 1), new MetricPoint(-1_000, 1))));
    }

    @Test
    void sourceUncertaintyAndChosenOutputPitchRemainSeparate() {
        LocalMetricFrame frame = frame();
        MetricRasterGrid grid = grid(frame, new MetricPoint(1.25, 1.25), 1, 0, 0, 1, 0.2, 30, 30);
        EvidenceResolution nativeResolution = new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
                OptionalDouble.of(2.4), 0.4, List.of(
                    new EvidenceResolution.PitchSample(0, OptionalDouble.of(2.4), 0.4),
                    new EvidenceResolution.PitchSample(10, OptionalDouble.of(2.8), 0.45)));
        EvidenceSnapshot nativeSnapshot = capture(gradientImage(16, 16), allValid(16, 16),
                List.of(metric(frame, 2, 3), metric(frame, 5, 3)), linearInverse(frame), grid,
                nativeResolution, 0.4, direct(), CancellationProbe.NONE);
        EvidenceSnapshot unknownSnapshot = capture(gradientImage(16, 16), allValid(16, 16),
                List.of(metric(frame, 2, 3), metric(frame, 5, 3)), linearInverse(frame), grid,
                EvidenceResolution.renderedOnly(0.4), 0.4, direct(), CancellationProbe.NONE);

        assertEquals(2.4, nativeSnapshot.resolution().nativePitchMeters().orElseThrow());
        assertEquals(0.4, nativeSnapshot.resolution().renderedPitchMeters());
        assertEquals(2.4, nativeSnapshot.resolution().effectivePitchMeters());
        assertEquals(0.2, nativeSnapshot.resolution().resampledPitchMeters().orElseThrow());
        assertEquals(nativeResolution.spatialPitchSamples(), nativeSnapshot.resolution().spatialPitchSamples());
        assertTrue(unknownSnapshot.resolution().nativePitchMeters().isEmpty());
        assertEquals(0.4, unknownSnapshot.resolution().effectivePitchMeters());
        assertEquals(0.2, unknownSnapshot.resolution().resampledPitchMeters().orElseThrow());
        assertTrue(unknownSnapshot.resolution().requiresResolutionReview());
    }

    @Test
    void validBlackInternalHolesAndBoundarySupportRemainDistinct() {
        LocalMetricFrame frame = frame();
        BufferedImage image = solidImage(12, 12, 0xff000000);
        boolean[] validity = allValid(12, 12);
        validity[5 * image.getWidth() + 5] = false;
        MetricRasterGrid grid = grid(frame, new MetricPoint(-0.1, -0.1), 1, 0, 0, 1, 1, 11, 11);
        EvidenceSnapshot snapshot = capture(image, validity,
                List.of(metric(frame, 2, 3), metric(frame, 8, 3)), linearInverse(frame), grid,
                EvidenceResolution.renderedOnly(1), 0.4, direct(), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("hot");

        assertEquals(0.0, field.sample(3, 3).orElseThrow(), 0.0);
        assertTrue(field.sample(0, 0).isEmpty());
        assertTrue(field.sample(5, 5).isEmpty());
        assertTrue(field.sample(4, 4).isPresent());
        assertTrue(field.sample(8, 8).isPresent());
    }

    @Test
    void boundedPostFilterPropagatesInvalidResampledCellSupport() {
        LocalMetricFrame frame = frame();
        BufferedImage image = gradientImage(12, 12);
        boolean[] validity = allValid(12, 12);
        validity[5 * image.getWidth() + 5] = false;
        MetricRasterGrid grid = grid(frame, new MetricPoint(0.25, 0.25), 1, 0, 0, 1, 1, 10, 10);
        RasterEvidenceCapture.FieldSpec filtered = RasterEvidenceCapture.FieldSpec.separable(
                "filtered", pixel -> (pixel >>> 16 & 0xff) / 255.0, lineage(),
                new double[] {1, 2, 1});

        EvidenceSnapshot snapshot = capture(image, validity,
                List.of(metric(frame, 2, 3), metric(frame, 8, 3)), linearInverse(frame), grid,
                EvidenceResolution.renderedOnly(1), 0.4, List.of(filtered), CancellationProbe.NONE);
        ScalarEvidenceField field = snapshot.fields().get("filtered");
        assertTrue(field.sampleBilinear(4.5, 4.5).isEmpty());
        assertTrue(!field.supportsInterpolationCell(4, 4));
    }

    @Test
    void cancellationIsCheckedPerOutputRowAndCannotPublishAPartialSnapshot() {
        LocalMetricFrame frame = frame();
        BufferedImage image = gradientImage(30, 30);
        MetricRasterGrid grid = grid(frame, new MetricPoint(1.25, 1.25), 1, 0, 0, 1, 0.5, 40, 40);
        AtomicInteger checks = new AtomicInteger();
        CancellationProbe cancellation = () -> checks.incrementAndGet() >= 5;

        assertThrows(CancellationException.class, () -> capture(image, allValid(30, 30),
                List.of(metric(frame, 3, 5), metric(frame, 12, 5)), linearInverse(frame), grid,
                EvidenceResolution.renderedOnly(1), 0.4, direct(), cancellation));
        assertTrue(checks.get() >= 5);
    }

    @Test
    void oversizedOutputFailsBeforeScalarMappingAndBeforeAllocation() {
        LocalMetricFrame frame = frame();
        AtomicInteger mappings = new AtomicInteger();
        RasterEvidenceCapture.FieldSpec field = RasterEvidenceCapture.FieldSpec.direct("hot", pixel -> {
            mappings.incrementAndGet();
            return 1;
        }, lineage());
        MetricRasterGrid grid = grid(frame, new MetricPoint(0, 0), 1, 0, 0, 1,
                0.1, 1_000_000, 1_000_000);

        assertThrows(IllegalArgumentException.class, () -> capture(gradientImage(4, 4), allValid(4, 4),
                List.of(metric(frame, 0, 0), metric(frame, 1, 0)),
                linearInverse(frame), grid, EvidenceResolution.renderedOnly(1), 0.1, List.of(field),
                CancellationProbe.NONE));
        assertEquals(0, mappings.get());
    }

    @Test
    void supportedFilterPeakIsRejectedByPureBudgetBeforeCallbacksOrRasterAllocation() {
        LocalMetricFrame frame = frame();
        AtomicInteger mappings = new AtomicInteger();
        RasterEvidenceCapture.FieldSpec filtered = RasterEvidenceCapture.FieldSpec.separable(
                "hot", pixel -> {
                    mappings.incrementAndGet();
                    return 1;
                }, lineage(), new double[] {1, 2, 1});
        long outputPixels = 3_500L * 3_557L;

        assertTrue(RasterEvidenceCapture.estimatedPeakWorkingBytes(
                256, outputPixels, List.of(filtered)) > RasterEvidenceCapture.MAX_WORKING_BYTES);
        MetricRasterGrid grid = grid(frame, new MetricPoint(0, 0),
                1, 0, 0, 1, 0.1, 3_500, 3_557);
        assertThrows(IllegalArgumentException.class, () -> capture(
                solidImage(16, 16, 0xffffffff), allValid(16, 16),
                List.of(metric(frame, 0, 0), metric(frame, 1, 0)),
                SupportedInputRasterTransform.localMetricAffine(frame,
                        new RasterPoint(0, 0), 1, 0, 0, 1),
                grid, EvidenceResolution.renderedOnly(1), 0.1,
                List.of(filtered), CancellationProbe.NONE));
        assertEquals(0, mappings.get());
    }

    @Test
    void retainedEvidenceRejectsUnderPeakCaseBeforeCallbacksOrRasterAllocation() {
        LocalMetricFrame frame = frame();
        AtomicInteger mappings = new AtomicInteger();
        AtomicInteger checkpoints = new AtomicInteger();
        List<RasterEvidenceCapture.FieldSpec> fields = directFields(16, mappings);
        int outputWidth = 2_000;
        int outputHeight = 1_000;
        long outputPixels = (long) outputWidth * outputHeight;
        long finalArraysOnly = (8L * outputPixels + outputPixels
                + (long) (outputWidth - 1) * (outputHeight - 1)) * fields.size();

        assertTrue(RasterEvidenceCapture.estimatedPeakWorkingBytes(
                4, outputPixels, fields) < RasterEvidenceCapture.MAX_WORKING_BYTES);
        assertEquals(435_277_288L, RasterEvidenceCapture.estimatedPeakWorkingBytes(
                4, outputPixels, fields));
        long retainedEstimate = RasterEvidenceCapture.estimatedRetainedEvidenceBytes(
                outputWidth, outputHeight,
                List.of(metric(frame, 0, 0), metric(frame, 1, 0)),
                EvidenceResolution.renderedOnly(1), "capture", "safe-source",
                linearInverse(frame), fields);
        assertEquals(319_952_016L, finalArraysOnly);
        assertTrue(retainedEstimate > finalArraysOnly);
        assertTrue(retainedEstimate > RasterEvidenceCapture.MAX_RETAINED_EVIDENCE_BYTES);
        assertTrue(RasterEvidenceCapture.MAX_RETAINED_EVIDENCE_BYTES
                < RasterEvidenceCapture.MAX_WORKING_BYTES);

        MetricRasterGrid grid = grid(frame, new MetricPoint(0, 0),
                1, 0, 0, 1, 0.1, outputWidth, outputHeight);
        assertThrows(IllegalArgumentException.class, () -> capture(
                solidImage(2, 2, 0xffffffff), allValid(2, 2),
                List.of(metric(frame, 0, 0), metric(frame, 1, 0)),
                linearInverse(frame), grid, EvidenceResolution.renderedOnly(1), 0.1,
                fields, () -> {
                    checkpoints.incrementAndGet();
                    return false;
                }));
        assertEquals(0, mappings.get());
        assertEquals(0, checkpoints.get());
    }

    @Test
    void retainedEvidenceBudgetAdmitsSmallControlAndRejectsOverflow() {
        List<RasterEvidenceCapture.FieldSpec> oneField = directFields(1, new AtomicInteger());
        LocalMetricFrame frame = frame();
        List<GeographicPoint> source = List.of(metric(frame, 0, 0), metric(frame, 1, 0));

        assertDoesNotThrow(() -> RasterEvidenceCapture.validateRetainedEvidenceBudget(
                4, 64, 64, source, EvidenceResolution.renderedOnly(1), "capture",
                "safe-source", linearInverse(frame), oneField));
        assertTrue(RasterEvidenceCapture.estimatedRetainedEvidenceBytes(
                64, 64, source, EvidenceResolution.renderedOnly(1), "capture",
                "safe-source", linearInverse(frame), oneField)
                <= RasterEvidenceCapture.MAX_RETAINED_EVIDENCE_BYTES);
        assertThrows(IllegalArgumentException.class, () ->
                RasterEvidenceCapture.estimatedRetainedEvidenceBytesFromCounts(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 2L, 1L,
                        1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1));
    }

    @Test
    void retainedEvidenceMetadataCountsRejectGeometryResolutionAndLineageBeforeAllocation() {
        List<RasterEvidenceCapture.FieldSpec> oneField = directFields(1, new AtomicInteger());

        assertThrows(IllegalArgumentException.class, () ->
                RasterEvidenceCapture.validateRetainedEvidenceBudgetFromCounts(
                        64, 64, 10_000_000L, 1L,
                        1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () ->
                RasterEvidenceCapture.validateRetainedEvidenceBudgetFromCounts(
                        64, 64, 2L, 10_000_000L,
                        1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1));
        assertThrows(IllegalArgumentException.class, () ->
                RasterEvidenceCapture.validateRetainedEvidenceBudgetFromCounts(
                        64, 64, 2L, 1L,
                        1L, 1L, 1L, 1L, 1L, 1L, 10_000_000L, 10_000_000L, 1));
        assertDoesNotThrow(() -> RasterEvidenceCapture.validateRetainedEvidenceBudgetFromCounts(
                64, 64, 2L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1));
    }

    @Test
    void retainedEvidenceCountsEveryLineageStringBackingArrayBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () ->
                RasterEvidenceCapture.validateRetainedEvidenceBudgetFromCounts(
                        64, 64, 2L, 1L,
                        1L, 1L, 1L, 1L, 1L, 1L,
                        6_000_000L, 6_000_000L, 1));
    }


    @Test
    void inputArraysAndExactInverseCallbackAreNotRetainedInSnapshotIdentity() {
        LocalMetricFrame frame = frame();
        BufferedImage image = gradientImage(12, 12);
        boolean[] validity = allValid(12, 12);
        MetricRasterGrid grid = grid(frame, new MetricPoint(0.25, 0.25), 1, 0, 0, 1, 1, 10, 10);
        EvidenceSnapshot snapshot = capture(image, validity,
                List.of(metric(frame, 2, 3), metric(frame, 8, 3)), linearInverse(frame), grid,
                EvidenceResolution.renderedOnly(1), 0.4, direct(), CancellationProbe.NONE);
        String identity = snapshot.canonicalHash();
        boolean[] copiedValidity = snapshot.fields().get("hot").copiedValidity();
        validity[0] = false;
        image.setRGB(1, 1, 0xff000000);

        assertEquals(identity, snapshot.canonicalHash());
        assertArrayEquals(copiedValidity, snapshot.fields().get("hot").copiedValidity());
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier.verify(snapshot);
    }

    private static EvidenceSnapshot capture(BufferedImage image, boolean[] validity,
            List<GeographicPoint> source, SupportedInputRasterTransform exactInverse,
            MetricRasterGrid grid, EvidenceResolution resolution, double decisionRadius,
            List<RasterEvidenceCapture.FieldSpec> fields, CancellationProbe cancellation) {
        return new RasterEvidenceCapture().capture("capture", image, validity, source,
                exactInverse, grid, resolution, decisionRadius, "safe-source",
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC, fields, cancellation);
    }

    private static List<RasterEvidenceCapture.FieldSpec> direct() {
        return List.of(RasterEvidenceCapture.FieldSpec.direct("hot",
                pixel -> (pixel >>> 16 & 0xff) / 255.0, lineage()));
    }

    private static List<RasterEvidenceCapture.FieldSpec> directFields(int count,
            AtomicInteger mappings) {
        List<RasterEvidenceCapture.FieldSpec> fields = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            fields.add(RasterEvidenceCapture.FieldSpec.direct("hot-" + index, pixel -> {
                mappings.incrementAndGet();
                return 1.0;
            }, lineage()));
        }
        return fields;
    }

    private static BufferedImage gradientImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = 20 * x + 5 * y;
                image.setRGB(x, y, 0xff000000 | value << 16);
            }
        }
        return image;
    }

    private static BufferedImage webGradientImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = 3 * x + 2 * y;
                image.setRGB(x, y, 0xff000000 | value << 16);
            }
        }
        return image;
    }

    private static BufferedImage solidImage(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static boolean[] allValid(int width, int height) {
        boolean[] result = new boolean[Math.multiplyExact(width, height)];
        java.util.Arrays.fill(result, true);
        return result;
    }

    private static LocalMetricFrame frame() {
        GeographicPoint origin = new GeographicPoint(42, 19);
        return LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
    }

    private static GeographicPoint metric(LocalMetricFrame frame, double x, double y) {
        return frame.toGeographic(new MetricPoint(x, y));
    }

    private static SupportedInputRasterTransform linearInverse(LocalMetricFrame frame) {
        return SupportedInputRasterTransform.localMetricAffine(
                frame, new RasterPoint(0, 0), 1, 0, 0, 1);
    }

    private static SupportedInputRasterTransform webMercatorCentered(
            LocalMetricFrame frame, int zoom, double rasterX, double rasterY) {
        double worldSize = Math.scalb(256.0, zoom);
        GeographicPoint origin = frame.origin();
        double worldX = (origin.longitudeDegrees() + 180.0) / 360.0 * worldSize;
        double latitudeRadians = Math.toRadians(origin.latitudeDegrees());
        double worldY = (1.0 - Math.log(Math.tan(latitudeRadians)
                + 1.0 / Math.cos(latitudeRadians)) / Math.PI) * 0.5 * worldSize;
        return SupportedInputRasterTransform.webMercator(
                zoom, worldX - rasterX - 0.5, worldY - rasterY - 0.5, 1.0);
    }

    private static MetricRasterGrid grid(LocalMetricFrame frame, MetricPoint firstCenter,
            double xEast, double xNorth, double yEast, double yNorth, double pitch,
            int width, int height) {
        return new MetricRasterGrid(frame, firstCenter, xEast, xNorth, yEast, yNorth,
                pitch, width, height);
    }

    private static EvidenceFieldLineage lineage() {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
    }

    private static EvidenceFieldLineage managedLineage() {
        return new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                EvidenceFieldLineage.DerivationKind.NATIVE_PALETTE_MAPPING, "hot",
                EvidenceCorrelationGroup.STRAVA_RENDERINGS, false);
    }
}
