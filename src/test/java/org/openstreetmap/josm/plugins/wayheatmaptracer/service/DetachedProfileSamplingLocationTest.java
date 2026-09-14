package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Contract tests for detached typed profile sampling locations. */
class DetachedProfileSamplingLocationTest {
    @Test
    void detachedProfilesReachActualCorridorAwareTrackerWithoutProjectedCoordinates() {
        LocalMetricFrame frame = frame();
        RasterMetricTransform rasterTransform = RasterMetricTransform.visible(
            new MetricPoint(0.0, 20.0), 1.0);
        List<CrossSectionProfile> detached = profiles(frame, rasterTransform, true);
        List<CrossSectionProfile> legacy = profiles(frame, rasterTransform, false);

        assertThrows(UnsupportedOperationException.class, () -> detached.get(0).anchor());
        assertEquals(new Point2D.Double(0.0, 20.0), detached.get(0).anchorScreen());
        assertEquals(25.0, detached.get(5).cumulativeGroundDistanceMeters(), 1e-12);

        CorridorAwareTracker tracker = new CorridorAwareTracker();
        CorridorAwareTracker.TrackingResult detachedResult = tracker.trackDetailed(
            detached, 1.0, JunctionContext.empty());
        CorridorAwareTracker.TrackingResult legacyResult = tracker.trackDetailed(
            legacy, 1.0, JunctionContext.empty());

        assertFalse(detachedResult.candidates().isEmpty(), "actual A must consume detached scalar profiles");
        assertFalse(legacyResult.candidates().isEmpty(), "legacy control must remain applicable");
        assertEquals(legacyResult.candidates().get(0).offsetsPx(),
            detachedResult.candidates().get(0).offsetsPx(),
            "A output must depend on typed metric/raster evidence, not projected coordinates");
    }

    @Test
    void detachedLocationRejectsMismatchedTypedCoordinates() {
        LocalMetricFrame frame = frame();
        RasterMetricTransform rasterTransform = RasterMetricTransform.visible(
            new MetricPoint(0.0, 20.0), 1.0);
        GeographicPoint geographic = frame.toGeographic(new MetricPoint(5.0, 0.0));

        assertThrows(IllegalArgumentException.class, () -> new DetachedProfileSamplingLocation(
            geographic, new MetricPoint(6.0, 0.0), new RasterPoint(5.0, 20.0),
            frame, rasterTransform, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new DetachedProfileSamplingLocation(
            geographic, new MetricPoint(5.0, 0.0), new RasterPoint(6.0, 20.0),
            frame, rasterTransform, 5.0));
        assertThrows(IllegalArgumentException.class, () -> new DetachedProfileSamplingLocation(
            geographic, new MetricPoint(5.0, 0.0), new RasterPoint(5.0, 20.0),
            frame, rasterTransform, Double.NaN));
    }

    private List<CrossSectionProfile> profiles(
        LocalMetricFrame frame, RasterMetricTransform rasterTransform, boolean detached
    ) {
        List<CrossSectionProfile> result = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            double chainage = index * 5.0;
            MetricPoint metric = new MetricPoint(chainage, 0.0);
            RasterPoint raster = rasterTransform.metricToPixelCenter(metric);
            ProfileSamplingLocation location;
            if (detached) {
                location = DetachedProfileSamplingLocation.at(
                    frame.toGeographic(metric), frame, rasterTransform, chainage);
            } else {
                location = new ProfileSamplingAnchor(
                    new EastNorth(1000.0 + index, 2000.0 + index), raster.x(), raster.y(), chainage);
            }
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -12; offset <= 12; offset++) {
                double distance = Math.abs(offset - 2.0);
                double intensity = distance <= 1.0 ? 0.92 : distance <= 3.0 ? 0.48 : 0.02;
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            result.add(new CrossSectionProfile(location, new Point2D.Double(0.0, 1.0),
                List.of(), true, samples));
        }
        return List.copyOf(result);
    }

    private LocalMetricFrame frame() {
        GeographicPoint origin = new GeographicPoint(50.0, 14.0);
        return LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.999, 14.0), new GeographicPoint(50.001, 14.003));
    }
}
