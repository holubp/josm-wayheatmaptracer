package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class ProfileIntensityInterpolatorTest {
    @Test
    void interpolatesAllScalarLevelsBetweenValidAdjacentSamples() {
        var profile = profile(List.of(
            new IntensitySample(0.0, 0.0, 0.2, 0.4, true),
            new IntensitySample(2.0, 1.0, 0.8, 0.6, true)
        ));

        var value = ProfileIntensityInterpolator.interpolate(profile, 0.5).orElseThrow();

        assertEquals(0.25, value.nativeIntensity(), 1e-12);
        assertEquals(0.35, value.lightFilteredIntensity(), 1e-12);
        assertEquals(0.45, value.standardFilteredIntensity(), 1e-12);
    }

    @Test
    void refusesToInterpolateAcrossInvalidRasterSupport() {
        var profile = profile(List.of(
            new IntensitySample(0.0, 0.2, 0.2, 0.2, true),
            new IntensitySample(1.0, 0.0, 0.0, 0.0, false),
            new IntensitySample(2.0, 0.8, 0.8, 0.8, true)
        ));

        assertTrue(ProfileIntensityInterpolator.interpolate(profile, 0.5).isEmpty());
        assertTrue(ProfileIntensityInterpolator.interpolate(profile, 1.5).isEmpty());
    }

    private RenderedHeatmapSampler.CrossSectionProfile profile(List<IntensitySample> samples) {
        return new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(0.0, 0.0),
            new Point2D.Double(), new Point2D.Double(0.0, 1.0), List.of(), true, samples);
    }
}
