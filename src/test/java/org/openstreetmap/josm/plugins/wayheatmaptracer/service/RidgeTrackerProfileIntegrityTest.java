package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

class RidgeTrackerProfileIntegrityTest {
    @Test
    @SuppressWarnings("unchecked")
    void filteringRetainsOriginalEvidenceFrameAndNativeSamples() throws Exception {
        var input = profiles(false);
        var contextClass = Class.forName(RidgeTracker.class.getName() + "$ContinuitySupportPolicy");
        var constructor = contextClass.getDeclaredConstructor(LegacyTrackerPolicy.class, double.class, double.class);
        constructor.setAccessible(true);
        var context = constructor.newInstance(LegacyTrackerPolicy.ORIGINAL_V02, 1.0, 1.0);
        var method = RidgeTracker.class.getDeclaredMethod("suppressLongitudinalNoise", List.class, contextClass);
        method.setAccessible(true);
        var output = (List<RenderedHeatmapSampler.CrossSectionProfile>) method.invoke(new RidgeTracker(), input, context);
        for (int i = 0; i < input.size(); i++) {
            assertSame(input.get(i).samplingAnchor(), output.get(i).samplingAnchor(),
                    "Peak filtering must not reset measured chainage or replace its coordinate owner");
            assertEquals(input.get(i).intensitySamples(), output.get(i).intensitySamples());
            assertEquals(input.get(i).projectedLateralTransform(), output.get(i).projectedLateralTransform());
        }
    }

    @Test
    void filteringDoesNotRequireAnActiveJosmCoordinateForTypedRasterProfiles() {
        var result = assertDoesNotThrow(() -> new RidgeTracker().track(profiles(true)));
        assertTrue(!result.isEmpty(), "Typed raster/chainage profiles contain usable persistent peaks");
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> profiles(boolean detached) {
        var result = new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
        for (int i = 0; i < 6; i++) {
            final int index = i;
            ProfileSamplingLocation location = detached ? new ProfileSamplingLocation() {
                @Override public RasterPoint rasterPoint() { return new RasterPoint(index * 10.0, 0.0); }
                @Override public double cumulativeGroundDistanceMeters() { return index * 3.0; }
            } : new ProfileSamplingAnchor(new EastNorth(i * 10.0, 0.0), i * 10.0, 0.0, i * 3.0);
            result.add(new RenderedHeatmapSampler.CrossSectionProfile(location, new Point2D.Double(0, 1),
                    List.of(new RenderedHeatmapSampler.CrossSectionPeak(-8, 0.9),
                            new RenderedHeatmapSampler.CrossSectionPeak(8, 0.7)), true,
                    List.of(new RenderedHeatmapSampler.IntensitySample(-8, 0.9, 0.9, 0.9, true),
                            new RenderedHeatmapSampler.IntensitySample(8, 0.7, 0.7, 0.7, true)),
                    Optional.of(new ProjectedLateralTransform(new EastNorth(i * 10.0, 0), 0, 2))));
        }
        return result;
    }
}
