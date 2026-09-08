package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class V022CoordinateFrameTest {
    @BeforeAll
    static void configureProjection() {
        org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(
            org.openstreetmap.josm.data.projection.Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test
    void t001ManagedPixelCenterRoundTripIncludesVirtualScaleAndInversePhase() {
        RasterMetricTransform transform = RasterMetricTransform.managed(
            new MetricPoint(100.0, 200.0), 6.0, 6.0);

        MetricPoint firstCenter = transform.pixelCenterToMetric(0.0, 0.0);

        assertEquals(new MetricPoint(103.0, 197.0), firstCenter);
        assertEquals(new RasterPoint(0.0, 0.0), transform.metricToPixelCenter(firstCenter));
    }

    @Test
    void t002VisibleIndexZeroReceivesNoManagedHalfPixelCorrection() {
        RasterMetricTransform transform = RasterMetricTransform.visible(new MetricPoint(100.0, 200.0), 0.4);

        assertEquals(new MetricPoint(100.0, 200.0), transform.pixelCenterToMetric(0.0, 0.0));
        assertEquals(new MetricPoint(100.4, 199.6), transform.pixelCenterToMetric(1.0, 1.0));
        RasterPoint roundTrip = transform.metricToPixelCenter(new MetricPoint(100.4, 199.6));
        assertEquals(1.0, roundTrip.x(), 1e-12);
        assertEquals(1.0, roundTrip.y(), 1e-12);
        assertEquals(RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, transform.originKind());
    }

    @Test
    void t003ProjectionUnitsGroundMetresRasterPitchAndNativePitchRemainDistinct() {
        EvidenceResolution resolution = EvidenceResolution.nativeSource(2.4, 0.4);
        double josmProjectionUnitsPerViewPixel = 0.389;
        var projection = org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        var center = projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(70.0, 15.0));
        double groundMetersPerViewPixel = org.openstreetmap.josm.plugins.wayheatmaptracer.service.ProjectionGroundScale
            .measure(List.of(center), josmProjectionUnitsPerViewPixel).representativeMetersPerProjectionUnit()
            * josmProjectionUnitsPerViewPixel;

        assertNotEquals(josmProjectionUnitsPerViewPixel, groundMetersPerViewPixel);
        assertNotEquals(josmProjectionUnitsPerViewPixel, resolution.renderedPitchMeters());
        assertNotEquals(resolution.renderedPitchMeters(), resolution.nativePitchMeters().orElseThrow());
        assertEquals(2.4, resolution.effectivePitchMeters(), 0.0);
    }

    @Test
    void t004AntimeridianFrameIsShortReversibleAndCertified() {
        GeographicPoint origin = new GeographicPoint(10.0, 179.95);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(9.99, 179.8), new GeographicPoint(10.01, -179.8));
        GeographicPoint source = new GeographicPoint(10.001, -179.95);

        MetricPoint metric = frame.toMetric(source);
        double greatCircle = new org.openstreetmap.josm.data.coor.LatLon(origin.latitudeDegrees(),
            origin.longitudeDegrees()).greatCircleDistance(new org.openstreetmap.josm.data.coor.LatLon(
                source.latitudeDegrees(), source.longitudeDegrees()));
        GeographicPoint restored = frame.toGeographic(metric);

        assertTrue(Math.abs(metric.xMeters()) < 20_000.0);
        assertTrue(Math.abs(Math.hypot(metric.xMeters(), metric.yMeters()) - greatCircle) / greatCircle < 0.001);
        assertEquals(source.latitudeDegrees(), restored.latitudeDegrees(), 1e-9);
        assertEquals(source.longitudeDegrees(), restored.longitudeDegrees(), 1e-9);
        assertTrue(frame.distortionCertificate().acceptable());
    }

    @Test
    void t005UnknownNativePitchDisablesNativeClaimsAndRequiresReview() {
        EvidenceResolution resolution = EvidenceResolution.renderedOnly(0.42);

        assertTrue(resolution.nativePitchMeters().isEmpty());
        assertTrue(resolution.requiresResolutionReview());
        assertEquals(0.42, resolution.effectivePitchMeters(), 0.0);
    }

    @Test
    void t006MeasuredChainageDoesNotAdoptDifferentConfiguredStep() {
        ProfileChainage chainage = ProfileChainage.measured(List.of(new MetricPoint(0.0, 0.0),
            new MetricPoint(3.0, 4.0), new MetricPoint(9.0, 4.0)), 1.56);

        assertEquals(List.of(0.0, 5.0, 11.0), chainage.cumulativeGroundMeters());
        assertEquals(1.56, chainage.configuredStepMeters(), 0.0);
    }
}
