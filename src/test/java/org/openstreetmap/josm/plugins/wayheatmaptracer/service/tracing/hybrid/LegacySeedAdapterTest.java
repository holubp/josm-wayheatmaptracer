package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;

class LegacySeedAdapterTest {
    @Test void legacyAdapterUsesProductionLegacyNumericsForLargeDeviation() {
        var profiles = IntStream.range(0, 5).mapToObj(i ->
                new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(i * 10, 0),
                        new Point2D.Double(i * 10, 0), new Point2D.Double(0, 1),
                        List.of(new RenderedHeatmapSampler.CrossSectionPeak(14, .85, 2, false)))).toList();
        var result = new LegacySeedAdapter().track(profiles, 1);
        assertFalse(result.isEmpty());
        assertEquals(List.of(14.0, 14.0, 14.0, 14.0, 14.0), result.get(0).offsetsPx());
        var original = new RidgeTracker().track(profiles, 1);
        assertEquals(original.size(), result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(original.get(i).offsetsPx(), result.get(i).offsetsPx());
            assertEquals(original.get(i).score(), result.get(i).score());
            assertEquals(original.get(i).screenPoints(), result.get(i).screenPoints());
        }
    }

    @Test void absentLegacyProfilesYieldNoInventedSeed() {
        assertTrue(new LegacySeedAdapter().track(List.of(), 1).isEmpty());
    }
}
