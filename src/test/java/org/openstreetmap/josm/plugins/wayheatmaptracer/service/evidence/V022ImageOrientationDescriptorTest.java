package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

class V022ImageOrientationDescriptorTest {
    private static final int SIZE = 101;
    private static final int CENTER = 50;

    @Test
    void rotatedNonUnitRasterTransformStillMeasuresMetricHorizontalDirection() {
        RasterMetricTransform transform = new RasterMetricTransform("rotated-nonunit-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(-60, -50),
            1.4, 0.45, -0.35, 1.1, 1.0);
        MetricPoint center = transform.pixelCenterToMetric(CENTER, CENTER);
        Fixture fixture = fixture(transform, point -> ridge(point.yMeters() - center.yMeters(), 0.02, 0.98, 1.2),
            (x, y, point) -> true);

        ImageOrientationDescriptor.Result result = describe(fixture, center, 1.0);

        assertEquals(18, result.responses().size());
        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED, result.support().status());
        assertTrue(result.support().modes().stream()
            .anyMatch(mode -> angularDistance(mode.peakBearingRadians(), 0.0) < Math.toRadians(3.0)));
        assertTrue(result.sampledRayPoints() <= EvidenceModelParameters.defaults().localization()
            .maximumOrientationSampleCount());
    }

    @Test
    void crossingRetainsTwoImageDirectionsWithoutAveraging() {
        RasterMetricTransform transform = identity();
        MetricPoint center = transform.pixelCenterToMetric(CENTER, CENTER);
        Fixture fixture = fixture(transform, point -> 0.02 + 0.98 * Math.max(
            gaussian(point.xMeters() - center.xMeters(), 1.0),
            gaussian(point.yMeters() - center.yMeters(), 1.0)), (x, y, point) -> true);

        ImageOrientationSupport support = describe(fixture, center, 1.0).support();

        assertTrue(support.modes().stream()
            .anyMatch(mode -> angularDistance(mode.peakBearingRadians(), 0.0) < Math.toRadians(3.0)));
        assertTrue(support.modes().stream()
            .anyMatch(mode -> angularDistance(mode.peakBearingRadians(), Math.PI / 2.0) < Math.toRadians(3.0)));
        assertTrue(support.modes().size() >= 2);
    }

    @Test
    void flatBrightFieldHasUnknownOrientation() {
        Fixture fixture = fixture(identity(), point -> 0.8, (x, y, point) -> true);

        ImageOrientationSupport support = describe(fixture, new MetricPoint(CENTER, CENTER), 1.0).support();

        assertEquals(ImageOrientationSupport.Status.UNKNOWN_FLAT, support.status());
        assertEquals(0.0, support.certainty(), 0.0);
        assertTrue(support.modes().isEmpty());
    }

    @Test
    void faintButCoherentRidgeRetainsMeasuredDirection() {
        Fixture fixture = fixture(identity(), point -> ridge(point.yMeters() - CENTER, 0.8, 0.05, 1.1),
            (x, y, point) -> true);

        ImageOrientationSupport support = describe(fixture, new MetricPoint(CENTER, CENTER), 1.0).support();

        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED, support.status());
        assertTrue(support.certainty() > 0.0);
        assertTrue(support.modes().stream()
            .anyMatch(mode -> angularDistance(mode.peakBearingRadians(), 0.0) < Math.toRadians(3.0)));
    }

    @Test
    void invalidCenterAndZeroWeightBilinearCornerBothFailClosed() {
        RasterMetricTransform transform = identity();
        Fixture invalidCenter = fixture(transform, point -> 1.0,
            (x, y, point) -> x != CENTER || y != CENTER);
        Fixture invalidCorner = fixture(transform, point -> 1.0,
            (x, y, point) -> x != CENTER + 1 || y != CENTER + 1);

        assertEquals(ImageOrientationSupport.Status.INVALID_CENTER,
            describe(invalidCenter, new MetricPoint(CENTER, CENTER), 1.0).support().status());
        assertEquals(ImageOrientationSupport.Status.INVALID_CENTER,
            describe(invalidCorner, new MetricPoint(CENTER + 0.25, CENTER + 0.25), 1.0).support().status());
    }

    @Test
    void physicalValidityQuadratureRequiresThreeQuartersOfEachRay() {
        MetricPoint center = new MetricPoint(CENTER, CENTER);
        Fixture fixture = fixture(identity(), point -> ridge(point.yMeters() - CENTER, 0.02, 0.98, 1.0),
            (x, y, point) -> !(point.xMeters() > CENTER + 1.5
                && point.xMeters() < CENTER + 5.5 && Math.abs(point.yMeters() - CENTER) < 0.75));

        ImageOrientationDescriptor.AngularResponse horizontal = describe(fixture, center, 1.0)
            .responses().get(0);

        assertTrue(horizontal.forwardValidFraction() < 0.75);
        assertEquals(ImageOrientationDescriptor.Visibility.BACKWARD_ONLY, horizontal.visibility());
        assertEquals(0.0, horizontal.response(), 0.0);
    }

    @Test
    void missingImmediateAngularNeighborCannotBracketAHorizontalMaximum() {
        MetricPoint center = new MetricPoint(CENTER, CENTER + 0.5);
        Fixture fixture = fixture(identity(), point -> ridge(point.yMeters() - center.yMeters(), 0.02, 0.98, 0.8),
            (x, y, point) -> {
                double dx = point.xMeters() - center.xMeters();
                double dy = point.yMeters() - center.yMeters();
                double radius = Math.hypot(dx, dy);
                double angle = ImageOrientationSupport.normalize(Math.atan2(dy, dx));
                return radius < 0.75 || dy <= 1.0
                    || angularDistance(angle, Math.toRadians(10.0)) > Math.toRadians(10.0);
            });

        ImageOrientationDescriptor.Result result = describe(fixture, center, 1.0);

        assertTrue(result.responses().get(0).response() > 0.0);
        assertTrue(result.responses().get(1).visibility()
            != ImageOrientationDescriptor.Visibility.TWO_SIDED);
        assertTrue(result.support().modes().stream().noneMatch(mode -> mode.contains(0.0)));
    }

    @Test
    void equalWraparoundBinsFormOneCircularPlateau() {
        double angle = Math.toRadians(175.0);
        RasterMetricTransform transform = new RasterMetricTransform("rotated-wrap-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            Math.cos(angle), Math.sin(angle), -Math.sin(angle), Math.cos(angle), 1.0);
        MetricPoint center = transform.pixelCenterToMetric(CENTER, CENTER);
        Fixture fixture = fixture(transform, point -> {
            double rasterNormal = -Math.sin(angle) * (point.xMeters() - center.xMeters())
                + Math.cos(angle) * (point.yMeters() - center.yMeters());
            return ridge(rasterNormal, 0.02, 0.98, 1.0);
        }, (x, y, point) -> true);

        ImageOrientationSupport support = describe(fixture, center, 1.0).support();

        assertEquals(1, support.modes().size());
        ImageOrientationSupport.AngularMode mode = support.modes().get(0);
        assertTrue(mode.wraps());
        assertEquals(Math.toRadians(170.0), mode.startRadians(), 1e-12);
        assertEquals(0.0, mode.endRadians(), 1e-12);
        assertEquals(0.0, support.mismatchSquared(angle), 1e-12);
    }

    @Test
    void singletonModeUsesInterpolatedPeakForIntervalEndpoints() {
        double angle = Math.toRadians(14.0);
        RasterMetricTransform transform = identity();
        MetricPoint center = transform.pixelCenterToMetric(CENTER, CENTER);
        Fixture fixture = fixture(transform, point -> {
            double rasterNormal = -Math.sin(angle) * (point.xMeters() - center.xMeters())
                + Math.cos(angle) * (point.yMeters() - center.yMeters());
            return ridge(rasterNormal, 0.02, 0.98, 1.0);
        }, (x, y, point) -> true);

        ImageOrientationSupport support = describe(fixture, center, 1.0).support();

        assertEquals(1, support.modes().size());
        ImageOrientationSupport.AngularMode mode = support.modes().get(0);
        assertEquals(mode.peakBearingRadians(), mode.startRadians(), 1e-12);
        assertEquals(mode.peakBearingRadians(), mode.endRadians(), 1e-12);
        assertTrue(mode.peakBearingRadians() > Math.toRadians(10.0));
        assertTrue(mode.peakBearingRadians() < Math.toRadians(20.0));
        assertEquals(0.0, support.mismatchSquared(mode.peakBearingRadians()), 1e-12);
        assertTrue(support.mismatchSquared(Math.toRadians(10.0))
            > support.mismatchSquared(mode.peakBearingRadians()));
    }

    @Test
    void negativeTinyBearingNormalizesInsideThePublicHalfCircle() {
        double normalized = ImageOrientationSupport.normalize(-Double.MIN_VALUE);

        assertEquals(0.0, normalized, 0.0);
        assertTrue(normalized >= 0.0 && normalized < Math.PI);
    }

    @Test
    void validGaussianMeasuresTwoSidedHorizontalSupportAtListedPhysicalPitches() {
        Fixture fixture = fixture(121, 101, identity(), point ->
            0.02 + 0.8 * gaussian(point.yMeters() - 50.0, 1.2),
            (x, y, point) -> true);

        for (double pitch : List.of(0.2, 0.3, 0.7, 1.4, 2.3)) {
            ImageOrientationDescriptor.Result result = describe(fixture, new MetricPoint(60, 50), pitch);

            assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                result.support().status(), "pitch=" + pitch);
            assertEquals(ImageOrientationDescriptor.Visibility.TWO_SIDED,
                result.responses().get(0).visibility(), "pitch=" + pitch);
            result.responses().forEach(response -> {
                assertTrue(response.headingRadians() >= 0.0 && response.headingRadians() < Math.PI,
                    "heading pitch=" + pitch);
                assertTrue(response.forwardValidFraction() >= 0.0
                    && response.forwardValidFraction() <= 1.0, "forward pitch=" + pitch);
                assertTrue(response.backwardValidFraction() >= 0.0
                    && response.backwardValidFraction() <= 1.0, "backward pitch=" + pitch);
            });
            result.support().modes().forEach(mode -> {
                assertTrue(mode.peakBearingRadians() >= 0.0
                    && mode.peakBearingRadians() < Math.PI, "peak pitch=" + pitch);
            });
        }
    }

    @Test
    void descriptorHonorsCancellationAndExplicitWorkLimit() {
        Fixture fixture = fixture(identity(), point -> ridge(point.yMeters() - CENTER, 0.02, 0.98, 1.0),
            (x, y, point) -> true);
        ImageOrientationDescriptor descriptor = new ImageOrientationDescriptor();

        assertThrows(CancellationException.class, () -> descriptor.describe(fixture.evidence(),
            fixture.field(), new MetricPoint(CENTER, CENTER), 1.0,
            EvidenceModelParameters.defaults(), () -> true));
        ImageOrientationDescriptor.Result limited = descriptor.describe(fixture.evidence(), fixture.field(),
            new MetricPoint(CENTER, CENTER), 1e-12, EvidenceModelParameters.defaults(), () -> false);
        assertEquals(ImageOrientationSupport.Status.RESOURCE_LIMIT, limited.support().status());
        assertEquals(0, limited.sampledRayPoints());
    }

    @Test
    void localizationRejectsCountsAboveTheVersionedDescriptorBudget() {
        EvidenceModelParameters.Localization defaults = EvidenceModelParameters.Localization.defaults();

        assertThrows(IllegalArgumentException.class, () -> new EvidenceModelParameters.Localization(
            Integer.MAX_VALUE, defaults.minimumOrientationRayMeters(),
            defaults.orientationRayLengthPitches(), defaults.maximumOrientationStepPitches(),
            defaults.minimumOrientationValidFraction(), defaults.orientationBackgroundQuantile(),
            defaults.orientationProminenceFraction(), Integer.MAX_VALUE,
            defaults.localModeProminenceFraction(), defaults.localModeShoulderFraction(),
            defaults.localModeCoreFraction(), defaults.routeProfileHalfWidthMeters()));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceModelParameters.Localization(
            defaults.orientationHeadingCount(), defaults.minimumOrientationRayMeters(),
            defaults.orientationRayLengthPitches(), defaults.maximumOrientationStepPitches(),
            defaults.minimumOrientationValidFraction(), defaults.orientationBackgroundQuantile(),
            defaults.orientationProminenceFraction(), defaults.maximumOrientationSampleCount() + 1,
            defaults.localModeProminenceFraction(), defaults.localModeShoulderFraction(),
            defaults.localModeCoreFraction(), defaults.routeProfileHalfWidthMeters()));
    }

    private static ImageOrientationDescriptor.Result describe(Fixture fixture, MetricPoint center,
        double pitch) {
        return new ImageOrientationDescriptor().describe(fixture.evidence(), fixture.field(), center, pitch);
    }

    private static Fixture fixture(RasterMetricTransform transform, MetricIntensity intensity,
        CellValidity validity) {
        return fixture(SIZE, SIZE, transform, intensity, validity);
    }

    private static Fixture fixture(int width, int height, RasterMetricTransform transform,
        MetricIntensity intensity, CellValidity validity) {
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                MetricPoint point = transform.pixelCenterToMetric(x, y);
                values[y * width + x] = Math.max(0.0, Math.min(1.0, intensity.value(point)));
                valid[y * width + x] = validity.valid(x, y, point);
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "analytic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid, lineage);
        MetricRegion footprint = new MetricRegion(List.of(List.of(
            transform.pixelCenterToMetric(-0.5, -0.5),
            transform.pixelCenterToMetric(width - 0.5, -0.5),
            transform.pixelCenterToMetric(width - 0.5, height - 0.5),
            transform.pixelCenterToMetric(-0.5, height - 0.5))));
        GeographicPoint geographicOrigin = new GeographicPoint(0, 0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(geographicOrigin,
            new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        EvidenceSnapshot evidence = new EvidenceSnapshot("orientation", frame, transform,
            EvidenceResolution.nativeSource(1, 1), footprint, footprint,
            Map.of("scalar", field), "analytic-orientation");
        return new Fixture(evidence, field);
    }

    private static RasterMetricTransform identity() {
        return new RasterMetricTransform("identity-v1",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0),
            1, 0, 0, 1, 1);
    }

    private static double ridge(double signedDistance, double background, double amplitude, double sigma) {
        return background + amplitude * gaussian(signedDistance, sigma);
    }

    private static double gaussian(double signedDistance, double sigma) {
        double normalized = signedDistance / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static double angularDistance(double first, double second) {
        double difference = Math.abs(ImageOrientationSupport.normalize(first)
            - ImageOrientationSupport.normalize(second));
        return Math.min(difference, Math.PI - difference);
    }

    private record Fixture(EvidenceSnapshot evidence, ScalarEvidenceField field) { }

    @FunctionalInterface
    private interface MetricIntensity {
        double value(MetricPoint point);
    }

    @FunctionalInterface
    private interface CellValidity {
        boolean valid(int x, int y, MetricPoint point);
    }
}
